package dk.kb.netarchivesuite.solrwayback.solr;

import com.google.gson.Gson;
import dk.kb.netarchivesuite.solrwayback.properties.PropertiesLoaderWeb;
import dk.kb.netarchivesuite.solrwayback.service.dto.statistics.QueryStatistics;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FieldStatsInfo;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Get stats through the solr <a href="https://solr.apache.org/guide/8_11/the-stats-component.html">stats component</a>.
 * Methods in this class
 */
public class SolrStats {
    // TODO: Call all methods through facade.
    private static final Logger log = LoggerFactory.getLogger(SolrStats.class);
    public static final List<String> interestingNumericFields =  Arrays.asList("content_length", "crawl_year", "content_text_length", "image_height", "image_width", "image_size");

    /**
     * Get standard solr stats for all fields given.
     * The solr documentation defines the standard stats <a href="https://solr.apache.org/guide/8_11/the-stats-component.html">here</a>
     * @param query   to generate stats for.
     * @param filters that are to be added to solr query.
     * @param fields  to return stats for.
     * @return all standard stats for all fields from query as a JSON string.
     */
    public static ArrayList<QueryStatistics> getStatsForFields(String query, List<String> filters, List<String> fields){
        if (fields.isEmpty()){
            throw new IllegalArgumentException("No fields have been specified for stats component.");
        }
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery(query);

        if (!(filters == null)){
            for (String filter: filters) {
                solrQuery.addFilterQuery(filter);
            }
        }

        for (String field: fields) {
            if (PropertiesLoaderWeb.STATS.contains(field)){
                solrQuery.setGetFieldStatistics(field);
            } else {
                log.warn("Stats can not be shown for field: " + field + " as it is not present in properties.");
                throw new IllegalArgumentException("Stats can not be shown for field: '" + field + "' as it is not present in properties.");
            }
        }

        QueryResponse response = NetarchiveSolrClient.query(solrQuery, true);
        Collection<FieldStatsInfo> fieldStatsInfos = response.getFieldStatsInfo().values();

        ArrayList<QueryStatistics> listOfStats = new ArrayList<>();
        for (FieldStatsInfo stat: fieldStatsInfos) {
            QueryStatistics dtoStat = new QueryStatistics();
            dtoStat.setAllStandardValuesFromSolrFieldStatsInfo(stat);
            listOfStats.add(dtoStat);
        }

        return listOfStats;

    }

    /**
     * Get percentiles for numeric fields
     * @param query to generate stats for.
     * @param percentiles to extract values for.
     * @param fields to return percentiles for.
     * @return percentiles for specified fields as a JSON string.
     */
    public static String getPercentilesForFields(String query, List<String> percentiles, List<String> fields){
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("No fields have been specified for stats component.");
        }
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery(query);

        List<Double> parsedPercentiles = new ArrayList<>();
        for (String percentile : percentiles) {
            parsedPercentiles.add(Double.parseDouble(percentile));
        }

        String allPercentiles = StringUtils.join(parsedPercentiles, ",");
        String percentileQuery = "{!percentiles='" + allPercentiles + "'}";

        // When giving this a text field it calculates nothing. Should probably throw a warning or something like that
        for (String field : fields) {
            if (PropertiesLoaderWeb.STATS.contains(field)) {
                solrQuery.setGetFieldStatistics(percentileQuery + field);
            } else {
                log.error("Percentiles can not be shown for field: " + field + " as it is not present in properties.");
                throw new IllegalArgumentException("Percentiles can not be shown for field: '" + field + "' as it is not present in properties.");
            }
        }

        try {
            QueryResponse response = NetarchiveSolrClient.query(solrQuery, true);
            Gson gson = new Gson();
            String stats = gson.toJson(response.getFieldStatsInfo().values());
            return stats;
        } catch (Exception e){
            throw new IllegalArgumentException("Percentiles have to be in range [0-100].");
        }


    }

}
