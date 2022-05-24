package dk.kb.netarchivesuite.solrwayback.pojos;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.tuple.Pair;

public class TweetUrl {
    private Pair<Integer, Integer> indices;

    private String expandedUrl;

    private String displayUrl;


    public TweetUrl() {
    }

    @JsonProperty("indices")
    private void unpackIndices(int[] indices) {
        this.indices = Pair.of(indices[0], indices[1]);
    }

    public Pair<Integer, Integer> getIndices() {
        return indices;
    }

    public String getExpandedUrl() {
        return expandedUrl;
    }

    public void setExpandedUrl(String expandedUrl) {
        this.expandedUrl = expandedUrl;
    }

    public String getDisplayUrl() {
        return displayUrl;
    }

    public void setDisplayUrl(String displayUrl) {
        this.displayUrl = displayUrl;
    }
}
