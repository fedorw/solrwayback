/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package dk.kb.netarchivesuite.solrwayback.solr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.kb.netarchivesuite.solrwayback.properties.PropertiesLoader;
import dk.kb.netarchivesuite.solrwayback.util.CollectionUtils;
import dk.kb.netarchivesuite.solrwayback.util.SolrUtils;
import org.apache.commons.io.IOUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrDocument;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Fake unit test as it requires a sharded Solr-setup running locally at
 * http://localhost:8983/solr/netarchivebuilder with documents in all shards.
 */
public class ShardStreamingTest {
    private static final Logger log = LoggerFactory.getLogger(ShardStreamingTest.class);

    public static final String LOCAL_SOLR = "http://localhost:8983/solr";
    public static final String COLLECTION = "netarchivebuilder";
    protected static SolrClient solrClient = new HttpSolrClient.Builder(LOCAL_SOLR + "/" + COLLECTION).build();
    protected static boolean AVAILABLE = false;


    @BeforeClass
    public static void checkAvailability() {
        SolrQuery query = new SolrQuery("*:*");
        try {
            solrClient.query(query);
            AVAILABLE = true;
            PropertiesLoader.SOLR_SERVER = LOCAL_SOLR + "/" + COLLECTION;
        } catch (Exception e) {
            log.warn("No local Solr available at '" + LOCAL_SOLR + "/" + COLLECTION + "'. Skipping unit test");
        }
    }

    @Test
    public void testPlainStream() {
        if (!AVAILABLE) {
            return;
        }
        assertTrue("There should be some hits from plain stream",
                   new SRequest().query("*:*").fields("id").solrClient(solrClient).stream().findAny().isPresent());
    }

    @Test
    public void testShardedSearch() throws IOException {
        if (!AVAILABLE) {
            return;
        }
        long allHits = new SRequest().query("*:*").fields("id").solrClient(solrClient)
                .stream().count();
        long shardHits = new SRequest().query("*:*").fields("id").solrClient(solrClient)
                .shards(SolrUtils.getShardNames(LOCAL_SOLR, COLLECTION).get(0))
                .stream().count();
        assertTrue("All hits (" + allHits + ") should be greater than single shard hits (" + shardHits + ")",
                   allHits > shardHits);
    }

    @Test
    public void checkShards() {
        if (!AVAILABLE) {
            return;
        }
        List<String> shardNames = SolrUtils.getShardNames(LOCAL_SOLR, COLLECTION);
        assertTrue("There should be more than 1 shards", shardNames.size() > 1);
        log.debug("Shard names: " + shardNames);
    }

    @Test
    public void testShardDivideAlways() {
        if (!AVAILABLE) {
            return;
        }
        SRequest request = new SRequest()
                .solrClient(solrClient)
                .query("*:*")
                .fields("id")
                .shardDivide("always")
                .maxResults(100);
        assertDocsEquals(request);
    }

    @Test
    public void testShardDivideAutoTrue() {
        if (!AVAILABLE) {
            return;
        }
        SRequest request = new SRequest()
                .solrClient(solrClient)
                .query("*:*")
                .fields("id")
                .shardDivide("auto")
                .autoShardDivideLimit(10)
                .maxResults(100);
        assertDocsEquals(request);
    }

    @Test
    public void testShardDivideExpandResources() {
        if (!AVAILABLE) {
            return;
        }
        SRequest request = new SRequest()
                .solrClient(solrClient)
                .query("*:*")
                .fields("id")
                .shardDivide("always")
                .maxResults(100)
                .expandResources(true);
        assertDocsEquals(request);
    }

    @Test
    public void testShardDivideTimeProximity() {
        if (!AVAILABLE) {
            return;
        }
        NetarchiveSolrClient.initialize(PropertiesLoader.SOLR_SERVER);
        SRequest request = new SRequest()
                .solrClient(solrClient)
                .query("*:*")
                .fields("id")
                .shardDivide("always")
                .maxResults(100)
                .timeProximityDeduplication("2023-10-10T19:47:00Z", "crawl_date");
        assertDocsEquals(request);
    }

    @Test
    public void testShardDivideSortDate() {
        if (!AVAILABLE) {
            return;
        }
        SRequest request = new SRequest()
                .solrClient(solrClient)
                .query("*:*")
                .fields("id")
                .shardDivide("always")
                .maxResults(100)
                .sort("crawl_date asc");
        assertDocsEquals(request);
    }

    @Test
    public void testShardDivideDeduplicate() {
        if (!AVAILABLE) {
            return;
        }
        PropertiesLoader.SOLR_SERVER = LOCAL_SOLR + "/" + COLLECTION;
        SRequest request = new SRequest()
                .solrClient(solrClient)
                .query("*:*")
                .fields("id")
                .shardDivide("always")
                .maxResults(100)
                .deduplicateField("domain");
        assertDocsEquals(request);
    }

    @Test
    public void testShardDivideAutoFalse() {
        if (!AVAILABLE) {
            return;
        }
        PropertiesLoader.SOLR_SERVER = LOCAL_SOLR + "/" + COLLECTION;
        SRequest request = new SRequest()
                .solrClient(solrClient)
                .query("*:*")
                .fields("id")
                .shardDivide("auto")
                .autoShardDivideLimit(Long.MAX_VALUE)
                .maxResults(100);
        assertDocsEquals(request);
    }

    @Test
    public void testComparator() {
        Comparator<SolrDocument> asc = SolrStreamShard.getDocumentComparator(new SRequest().sort(
                "crawl_date asc, id asc"));
        Comparator<SolrDocument> desc = SolrStreamShard.getDocumentComparator(new SRequest().sort(
                "crawl_date desc, id asc"));

        SolrDocument doc1 = new SolrDocument();
        doc1.setField("id", "1");
        doc1.setField("crawl_date", new Date().getTime());

        SolrDocument doc2 = new SolrDocument();
        doc2.setField("id", "2");
        doc2.setField("crawl_date", new Date().getTime()+100);

        assertEquals("Comparison of doc1 and doc2 should yield expected order for asc",
                     -1, asc.compare(doc1, doc2));
        assertEquals("Comparison of doc2 and doc1 should yield expected order for asc",
                     1, asc.compare(doc2, doc1));
        assertEquals("Comparison of doc1 and doc2 should yield expected order for desc",
                     1, desc.compare(doc1, doc2));
    }

    /**
     * Calls {@link SolrStreamShard#iterateSharded(SRequest, List)} on the {@code request} and extracts all IDs,
     * also sets {@link SRequest#shardDivide} to {@code false} and call {@link SolrGenericStreaming#iterate(SRequest)}
     * and extracts all IDs. Finally the extracted IDs are compared.
     * @param request a request to test shard division.
     */
    private void assertDocsEquals(SRequest request) {
        Iterator<SolrDocument> plainDocs = SolrGenericStreaming.iterate(request.deepCopy().shardDivide("never"));
        try (CollectionUtils.CloseableIterator<SolrDocument> shardDocs = SolrStreamShard.iterateStrategy(request)) {
            long count = 0;
            while (plainDocs.hasNext()) {
                assertTrue("For doc #" + count + ", plainDocs has next so shardDocs should also have next",
                           shardDocs.hasNext());
                assertEquals("For doc #" + count + ", id for plain and shard should be equal",
                             plainDocs.next().get("id"), shardDocs.next().get("id"));
                count++;
            }
            assertFalse("After processing, shardDocs should have no more documents", shardDocs.hasNext());
            log.debug("Finished comparing {} documents", count);
        }
    }


}
