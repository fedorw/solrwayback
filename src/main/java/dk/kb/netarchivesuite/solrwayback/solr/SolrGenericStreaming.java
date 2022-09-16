package dk.kb.netarchivesuite.solrwayback.solr;

import dk.kb.netarchivesuite.solrwayback.parsers.ArcParserFileResolver;
import dk.kb.netarchivesuite.solrwayback.parsers.HtmlParserUrlRewriter;
import dk.kb.netarchivesuite.solrwayback.properties.PropertiesLoader;
import dk.kb.netarchivesuite.solrwayback.service.dto.ArcEntry;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.CursorMarkParams;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.StatsParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.apache.commons.lang3.StringUtils.join;

/**
 * Cursormark based chunking search client allowing for arbitrary sized result sets.
 */
public class SolrGenericStreaming implements Iterable<SolrDocument> {
  private static final Logger log = LoggerFactory.getLogger(SolrGenericStreaming.class);

  /**
   * Default page size (rows) for the cursormark paging.
   */
  public static final int DEFAULT_PAGESIZE = 1000;

  /**
   * Default sort used when exporting. Ends with tie breaking on id.
   */
  public static final String DEFAULT_SORT = "score desc, id asc";

  /**
   * Default maximum number of elements when the request requires unique results.
   * If this limit is exceeded during processing, an exception is thrown.
   * The uniquifier uses a HashSet (which is a Map underneath the hood) for tracking
   * unique values. Each entry takes up about ~150 bytes plus the value itself, so
   * something like 250 bytes/entry as a rule of thumb. The default MAX_UNIQUE is thus
   * about 1.25GB of maximum heap.
   */
  public static final int DEFAULT_MAX_UNIQUE = 5_000_000;

  private final SolrClient solrClient;
  private final boolean expandResources;

  private final SolrQuery solrQuery;
  private List<String> fields;
  private final int pageSize;
  private String cursorMark = CursorMarkParams.CURSOR_MARK_START;

  private final Set<String> uniqueTracker;
  private final int maxUnique;
  private long duplicatesRemoved = 0;
  private SolrDocumentList undelivered = null; // Leftover form previous call to keep deliveries below pageSize
  private boolean hasFinished = false;

  private String deduplicateField; // If set, timeProximity is used
  private Object lastStreamDeduplicateValue = null; // Used with timeProximity

  /**
   * The default SolrClient is simple and non-caching as streaming exports typically makes unique requests.
   */
  private static SolrClient defaultSolrClient = new HttpSolrClient.Builder(PropertiesLoader.SOLR_SERVER).build();

  // TODO: Make graph traversal of JavaScript & CSS-includes with expandResources

  /**
   * Streams documents that are closest in time to crawl_time, removing duplicates.
   *
   * @param solrClient       used for issuing Solr requests. If null, the {@link #defaultSolrClient} is used.
   * @param fields           fields to export. deduplicatefield will be added to this is not already present.
   * @param expandResources  if true, embedded resources for HTML pages are extracted and added to the delivered
   *                         lists of Solr Documents.
   *                         Note: Indirect references (through JavaScript & CSS) are not followed.
   * @param ensureUnique     if true, unique documents are guaranteed. This is only sane if expandResources is true.
   *                         Note that a HashSet is created to keep track of encountered documents and will impose
   *                         a memory overhead linear to the number of results.
   * @param maxUnique        the maximum number of uniques to track when ensureUnique is true.
   *                         If the number of uniques exceeds this limit, an exception is thrown.
   *                         Specifying null means {@link #DEFAULT_MAX_UNIQUE} will be used.
   * @param idealTime        The time that the resources should be closest to, stated as a Solr timestamp
   *                         {@code YYYY-MM-DDTHH:mm:SSZ}. Also supports {@code oldest} and {@code newest} as values.
   * @param deduplicateField The field to use for de-duplication. This is typically {@code url}.
   *                         Note: deduplicateField does not affect expandResources. Set ensureUnique to true if
   *                         if expandResources is true and uniqueness must also be guaranteed for resources.
   * @param query            standard Solr query.
   * @param filterQueries    optional Solr filter queries. For performance, 0 or 1 filter query is recommended.
   *                         If multiple filters are to be used, consider collapsing them into one:
   *                         {@code ["foo", "bar"]} → {@code ["(foo) AND (bar)"]}.
   */
  // TODO: When https://github.com/ukwa/webarchive-discovery/issues/214 gets implemented it should be possible to use Last-Modied/Date from HTTP headers instead of crawl_date
  public static SolrGenericStreaming timeProximity(
          SolrClient solrClient, List<String> fields,
          boolean expandResources, boolean ensureUnique, Integer maxUnique,
          String idealTime, String deduplicateField,
          String query, String... filterQueries) throws IllegalArgumentException {
    // Extra steps for timeProximity:
    // 1) Construct sort "<deduplicateField> asc, abs(sub(ms(2014-01-03T11:56:58Z), crawl_date)) asc")
    // 2) Keep track of latest received deduplicateField. When the value changes, accept the document and
    //    remember the new value for future deduplication
    String origo = idealTime;
    if ("newest".equals(idealTime)) {
      origo = "9999-12-31T23:59:59Z";
    } else if ("oldest".equals(idealTime)) {
      origo = "0001-01-01T00:00:01Z";
    } else if (!ISO_TIME.matcher(idealTime).matches()) {
      throw new IllegalArgumentException(
              "The idealTime '" + idealTime + "' does not match 'oldest', 'newest', 'YYYY-MM-DDTHH:mm:SSZ' or " +
              "'YYYY-MM-DDTHH:mm:SS.sssZ");
    }
    if (ensureUnique && !expandResources) {
      log.warn("timeProximity: ensureUnique == true with expandResources == false. " +
               "This practically never makes sense and will only impose unnecessary memory overhead");
    }
    if (deduplicateField == null) {
      throw new NullPointerException("deduplicateField == null which is not allowed for timeProximity");
    }

    String sort = String.format(Locale.ROOT, "%s asc, abs(sub(ms(%s), crawl_date)) asc", deduplicateField, origo);
    SolrQuery timeQuery = buildBaseQuery(DEFAULT_PAGESIZE, fields, query, filterQueries, sort);
    return new SolrGenericStreaming(solrClient, timeQuery, expandResources, ensureUnique, maxUnique, deduplicateField);
  }
  private static final Pattern ISO_TIME = Pattern.compile("[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[012][0-9]:[0-5][0-9][.]?[0-9]?[0-9]?[0-9]?Z");

  /**
   * Default page size 1000, expandResources=false and avoidDuplicates=false.
   * @param solrClient    used for issuing Solr requests. If null is specified, {@link #defaultSolrClient} is used.
   * @param fields        the fields to export.
   * @param query         standard Solr query.
   * @param filterQueries optional Solr filter queries. For performance, 0 or 1 filter query is recommended.
   *                      If multiple filters are to be used, consider collapsing them into one:
   *                      {@code ["foo", "bar"]} → {@code ["(foo) AND (bar)"]}.
   */
  public SolrGenericStreaming(SolrClient solrClient, List<String> fields, String query, String... filterQueries) {
    this(solrClient, buildBaseQuery(DEFAULT_PAGESIZE, fields, query, filterQueries, DEFAULT_SORT),
         false, false, 0, null);
  }

  /**
   * @param solrClient       used for issuing Solr requests. If null is specified, {@link #defaultSolrClient} is used.
   * @param pageSize         paging size. 1000-100,000 depending on fields.
   * @param fields           the fields to export.
   * @param expandResources  if true, embedded resources for HTML pages are extracted and added to the delivered
   *                         lists of Solr Documents.
   *                         Note: Indirect references (through JavaScript & CSS) are not followed.
   * @param ensureUnique     if true, unique documents are guaranteed. This is only sane if expandResources is true.
   *                         Note that a HashSet is created to keep track of encountered documents and will impose
   *                         a memory overhead linear to the number of results.
   * @param query            standard Solr query.
   * @param filterQueries    optional Solr filter queries. For performance, 0 or 1 filter query is recommended.
   *                         If multiple filters are to be used, consider collapsing them into one:
   *                         {@code ["foo", "bar"]} → {@code ["(foo) AND (bar)"]}.
   */
  public SolrGenericStreaming(
          SolrClient solrClient, int pageSize, List<String> fields, boolean expandResources, boolean ensureUnique,
          String query, String... filterQueries) {
    this(solrClient, buildBaseQuery(pageSize, fields, query, filterQueries, DEFAULT_SORT),
         expandResources, ensureUnique, DEFAULT_MAX_UNIQUE, null);
  }

  /**
   * Advanced version where the user provides the SolrQuery object. Not recommended for casual use.
   *
   * If {@code fl} is not already set in solrQuery it will be set to {@code source_file_path,source_file_offset}.
   * If {@code cursorMark} is not already set in solrQuery it will be set to {@code *}.
   * If {@code rows} is not already set in solrQuery it will be set to {@link #DEFAULT_PAGESIZE}.
   * If {@code sort} is not already set in solrQuery it will be set to {@link #DEFAULT_SORT}.
   * If {@code sort} does not end with {@code id asc} or {@code id desc}, {@code id asc} will be appended.
   * If expandResources is true and {@code fl} in solrQuery does not contain {@code content_type_norm},
   * {code source_file_path} and {@code source_file_offset} they will be added.
   * If ensureUnique is true and {@code fl} in solrQuery does not contain the field {@code id} it will be added.
   * If deduplicateField is specified and {@code fl} in solrQuery does not already contain the field it will be added.
   * If deduplicateField is specified and {@code sort} does not already have it as primary sort field it will be added.
   * {@code facets}, {@code stats} and {@code hl} will always be set to false, no matter their initial value.
   *
   * @param solrClient       used for issuing Solr requests. If null is specified, {@link #defaultSolrClient} is used.
   * @param solrQuery        a Solr query object ready for use.
   * @param expandResources  if true, embedded resources for HTML pages are extracted and added to the delivered
   *                         lists of Solr Documents.
   *                         Note: Indirect references (through JavaScript & CSS) are not followed.
   * @param ensureUnique     if true, unique documents are guaranteed. This is only sane if expandResources is true.
   *                         Note that a HashSet is created to keep track of encountered documents and will impose
   *                         a memory overhead linear to the number of results.
   * @param maxUnique        the maximum number of uniques to track when ensureUnique is true.
   *                         If the number of uniques exceeds this limit, an exception is thrown.
   *                         Specifying null means {@link #DEFAULT_MAX_UNIQUE} will be used.
   * @param deduplicateField if not null, the value for the given field for a document will be compared to the value
   *                         for the previous document. If they are equal, the current document will be skipped.
   * @throws IllegalArgumentException if solrQuery has no {@code fl} element.
   */
  public SolrGenericStreaming(
          SolrClient solrClient, SolrQuery solrQuery,
          boolean expandResources, boolean ensureUnique, Integer maxUnique,
          String deduplicateField) throws IllegalArgumentException {
    this.solrClient = Optional.ofNullable(solrClient).orElse(defaultSolrClient);
    this.solrQuery = solrQuery;
    this.expandResources = expandResources;
    this.uniqueTracker = ensureUnique ? new HashSet<>() : null;
    this.maxUnique = maxUnique == null ? DEFAULT_MAX_UNIQUE : maxUnique;
    this.fields = Arrays.asList(solrQuery.getFields().split(","));
    this.pageSize = solrQuery.getRows();
    this.deduplicateField = deduplicateField;

    solrQuery.set(CommonParams.FL, solrQuery.get(CommonParams.FL, "source_file_path,source_file_offset"));
    cursorMark = solrQuery.get(CursorMarkParams.CURSOR_MARK_PARAM, CursorMarkParams.CURSOR_MARK_START);
    solrQuery.set(CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);
    solrQuery.set(CommonParams.ROWS, solrQuery.get(CommonParams.ROWS, Integer.toString(DEFAULT_PAGESIZE)));

    Set<String> fl = new HashSet<>(Arrays.asList(solrQuery.get(CommonParams.FL).split(", *")));
    if (expandResources) {
      fl.add("content_type_norm");  // Needed to determine if a resource is a webpage
      fl.add("source_file_path");   // Needed to fetch the webpage for link extraction
      fl.add("source_file_offset"); // Needed to fetch the webpage for link extraction
    }
    if (expandResources && ensureUnique) {
      fl.add("id"); // id is shorter than sourcefile@offset in webarchive-discovery compatible indexes
    }

    String sort = solrQuery.get(CommonParams.SORT, DEFAULT_SORT);
    if (!(sort.endsWith("id asc") || sort.endsWith("id desc"))) {
      sort = sort + ", id asc"; // A tie breaker is needed when using cursormark
    }
    if (deduplicateField != null) {
      fl.add(deduplicateField);
      if (!sort.startsWith(deduplicateField)) {
        solrQuery.set(CommonParams.SORT, deduplicateField + " asc, " + sort);
      }
    }
    solrQuery.set(CommonParams.SORT, sort);

    solrQuery.set(CommonParams.FL, String.join(",", fl));

    solrQuery.set(FacetParams.FACET, false);
    solrQuery.set(StatsParams.STATS, false);
    solrQuery.set(HighlightParams.HIGHLIGHT, false);
  }

  /**
   * Stream the Solr response one document at a time.
   * @return a stream of SolrDocuments.
   */
  public Stream<SolrDocument> stream() {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), 0), false);
  }

  /**
   * @return an iterator of SolrDocuments.
   */
  @Override
  public Iterator<SolrDocument> iterator() {
    return new Iterator<SolrDocument>() {
      SolrDocumentList list = null;
      int index = 0;

      @Override
      public boolean hasNext() {
        // Request new list if it is depleted and there are more document lists available
        if ((list == null || index == list.size()) && !hasFinished()) {
          try {
            list = nextDocuments();
            index = 0;
          } catch (Exception e) {
            throw new RuntimeException("Exception requesting next batch", e);
          }
        }
        // Remove list if it is depleted
        if (list != null && index == list.size()) {
          list = null;
        }
        return list != null;
      }

      @Override
      public SolrDocument next() {
        if (!hasNext()) {
          throw new NoSuchElementException("No more elements");
        }
        return list.get(index++);
      }
    };
  }

  private static SolrQuery buildBaseQuery(
          int pageSize, List<String> fields, String query, String[] filterQueries, String sort) {
    SolrQuery solrQuery = new SolrQuery();
    solrQuery.add(CommonParams.FL, join(fields, ","));
    solrQuery.add(CommonParams.SORT, sort);
    solrQuery.setRows(pageSize);
    solrQuery.setQuery(query);
    if (filterQueries != null) {
      for (String filter: filterQueries) {
        if (filter != null) {
          solrQuery.addFilterQuery(filter);
        }
      }
    }
    return solrQuery;
  }

  /**
   * @return at least 1 and at most {@link #pageSize} documents or null if there are no more documents.
   * @throws SolrServerException if Solr could not handle a request for new documents.
   * @throws IOException if general communication with Solr failed.
   */
  public SolrDocumentList nextDocuments() throws SolrServerException, IOException {
    while (hasFinished) {

      // Return batch if undelivered contains any documents
      if (undelivered != null && undelivered.size() > 0) {
        return nextPageUndelivered();
      }

      // No more documents buffered, attempt to require new documents
      solrQuery.set(CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);
      QueryResponse rsp = solrClient.query(solrQuery, METHOD.POST);
      cursorMark = rsp.getNextCursorMark();
      if (rsp.getResults().getNumFound() == 0L) { // No more documents
        hasFinished = true;
        return null;
      }

      undelivered = rsp.getResults();

      if (deduplicateField != null) {
        streamDeduplicate(undelivered);
      }
      if (expandResources) {
        expandResources(undelivered);
      }
      if (uniqueTracker != null) {
        removeDuplicates(undelivered);
      }
      // NetarchiveSolrClient.mergeInto(undelivered, documents);

      // Loop as deduplication & unique might mean that the current batch is empty
    }
    return null; // Finished and no more documents
  }

  /**
   * If there are more than {@link #pageSize} documents in {@link #undelivered}, exactly pageSize documents are returned and
   * the rest are kept in {@link #undelivered}. Else the full amount of documents in {@link #undelivered} is returned.
   */
  private SolrDocumentList nextPageUndelivered() {
    if (undelivered == null || undelivered.size() < pageSize) {
      SolrDocumentList oldUndelivered = undelivered;
      undelivered = null;
      return oldUndelivered;
    }

    SolrDocumentList batch = new SolrDocumentList();
    batch.addAll(undelivered.subList(0, pageSize));

    SolrDocumentList newUndelivered = new SolrDocumentList();
    newUndelivered.addAll(undelivered.subList(pageSize, undelivered.size()));
    undelivered = newUndelivered;

    return batch;
  }

  private void expandResources(SolrDocumentList documents) {
    int initialSize = documents.size();
    for (int i = 0 ; i < initialSize ; i++) {
      if ("html".equals(documents.get(i).getFieldValue("content_type_norm"))) {
        documents.addAll(getHTMLResources(documents.get(i)));
      }
    }
  }

  private SolrDocumentList getHTMLResources(SolrDocument html) {
    try {
      String sourceFile = html.getFieldValue("source_file_path").toString();
      Long offset = Long.parseLong(html.getFieldValue("source_file_offset").toString());
      ArcEntry arc= ArcParserFileResolver.getArcEntry(sourceFile, offset);
      HashSet<String> resources = HtmlParserUrlRewriter.getResourceLinksForHtmlFromArc(arc);
      
      return NetarchiveSolrClient.getInstance().findNearestDocuments(resources, arc.getCrawlDate(), join(fields, ","));
    } catch (Exception e) {
      log.warn("Unable to get resources for Solrdocument " + html, e);
      return new SolrDocumentList();
    }
  }

  public long getDuplicatesRemoveCount() {
    return duplicatesRemoved;
  }

  public boolean hasFinished() {
    return hasFinished;
  }

  private void removeDuplicates(SolrDocumentList documents) {
    List<SolrDocument> unique = new ArrayList<>(documents.size());

    // Important: We ensure that first version (in sort order) of duplicate entries win
    documents.forEach(doc -> {
      if (uniqueTracker.add(getID(doc))) {
        unique.add(doc);
      } else {
        duplicatesRemoved++;
      }
    });

    documents.clear();
    documents.addAll(unique);
  }

  /**
   * Streaming deduplication where the incoming documents are expected to be in order.
   */
  private void streamDeduplicate(SolrDocumentList documents) {
    List<SolrDocument> unique = new ArrayList<>(documents.size());
    for (SolrDocument doc: documents) {
      if (lastStreamDeduplicateValue == null ||
          !lastStreamDeduplicateValue.equals(doc.getFieldValue(deduplicateField))) {
          lastStreamDeduplicateValue = doc.getFieldValue(deduplicateField);
          unique.add(doc);
      } else {
        duplicatesRemoved++;
      }
    }
    documents.clear();
    documents.addAll(unique);
  }

  private String getID(SolrDocument solrDocument) {
    return solrDocument.getFieldValue("id").toString();
    //return solrDocument.getFieldValue("source_file_path") + "@" +
    //       solrDocument.getFieldValue("source_file_offset");
  }
}
