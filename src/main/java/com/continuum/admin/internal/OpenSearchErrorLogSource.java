package com.continuum.admin.internal;

import com.continuum.admin.ErrorLogSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * {@link ErrorLogSource} over the OpenSearch search API: one terms aggregation on the ECS event code, with
 * first/last seen, trend histogram, nodes and a sample message as label. Field names, level values, index
 * pattern and sizes come from {@link ErrorLogProperties}.
 */
public class OpenSearchErrorLogSource implements ErrorLogSource {

    private final RestClient http;
    private final ErrorLogProperties props;

    public OpenSearchErrorLogSource(RestClient http, ErrorLogProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public List<Group> groupByEventCode(Instant from, Instant to) {
        JsonNode response = http.post().uri("/{index}/_search", props.indexPattern())
                .body(searchBody(from, to)).retrieve().body(JsonNode.class);
        return parse(response);
    }

    List<Group> parse(JsonNode response) {
        List<Group> groups = new ArrayList<>();
        if (response == null) {
            return groups;
        }
        for (JsonNode b : response.path("aggregations").path("codes").path("buckets")) {
            List<String> nodes = new ArrayList<>();
            b.path("nodes").path("buckets").forEach(n -> nodes.add(n.path("key").asText()));
            List<Trend> trend = new ArrayList<>();
            b.path("trend").path("buckets").forEach(t -> trend.add(
                    new Trend(Instant.ofEpochMilli(t.path("key").asLong()), t.path("doc_count").asLong())));
            JsonNode levels = b.path("levels").path("buckets");
            String level = levels.isEmpty() ? null : levels.get(0).path("key").asText();
            JsonNode hits = b.path("sample").path("hits").path("hits");
            String label = hits.isEmpty() ? null : textOrNull(hits.get(0).path("_source"), props.messageField());
            groups.add(new Group(b.path("key").asText(), label, level, b.path("doc_count").asLong(),
                    millis(b.path("first").path("value")), millis(b.path("last").path("value")), nodes, trend, null));
        }
        return groups;
    }

    private Map<String, Object> searchBody(Instant from, Instant to) {
        Map<String, Object> range = new LinkedHashMap<>();
        if (from != null) {
            range.put("gte", from.toString());
        }
        if (to != null) {
            range.put("lt", to.toString());
        }
        List<Object> filter = new ArrayList<>();
        filter.add(Map.of("terms", Map.of(props.levelField(), props.levels())));
        filter.add(Map.of("exists", Map.of("field", props.eventCodeField())));
        if (!range.isEmpty()) {
            filter.add(Map.of("range", Map.of(props.timestampField(), range)));
        }
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("first", Map.of("min", Map.of("field", props.timestampField())));
        sub.put("last", Map.of("max", Map.of("field", props.timestampField())));
        sub.put("levels", Map.of("terms", Map.of("field", props.levelField(), "size", 1)));
        sub.put("nodes", Map.of("terms", Map.of("field", props.nodeField(), "size", props.maxNodes())));
        sub.put("trend", Map.of("date_histogram",
                Map.of("field", props.timestampField(), "fixed_interval", props.trendInterval())));
        sub.put("sample", Map.of("top_hits", Map.of("size", 1, "_source", List.of(props.messageField()))));
        Map<String, Object> codes = new LinkedHashMap<>();
        codes.put("terms", Map.of("field", props.eventCodeField(), "size", props.maxGroups()));
        codes.put("aggs", sub);
        return Map.of("size", 0, "query", Map.of("bool", Map.of("filter", filter)), "aggs", Map.of("codes", codes));
    }

    private static Instant millis(JsonNode value) {
        return value.isNumber() ? Instant.ofEpochMilli(value.asLong()) : null;
    }

    private static String textOrNull(JsonNode source, String dottedField) {
        JsonNode flat = source.get(dottedField);
        if (flat != null && !flat.isNull()) {
            return flat.asText();
        }
        JsonNode cur = source;
        for (String part : dottedField.split("\\.")) {
            cur = cur.path(part);
        }
        return cur.isMissingNode() || cur.isNull() ? null : cur.asText();
    }
}
