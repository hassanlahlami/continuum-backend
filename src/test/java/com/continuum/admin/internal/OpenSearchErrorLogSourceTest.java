package com.continuum.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.admin.ErrorLogSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing of an OpenSearch terms-aggregation response into error groups. */
class OpenSearchErrorLogSourceTest {

    private final ErrorLogProperties props = new ErrorLogProperties("", "", "", "continuum-logs-*", "@timestamp",
            "log.level", "event.code", "message", "service.node.name", List.of("ERROR", "WARN"), "1d", 200, 20,
            "trace.id", "span.id", "span.parent.id", "service.name", "event.action", 500);

    @Test
    void parsesBuckets() throws Exception {
        String json = """
                {"aggregations":{"codes":{"buckets":[{"key":"CTN-INT-0102","doc_count":5,
                 "first":{"value":1792000000000},"last":{"value":1792086400000},
                 "levels":{"buckets":[{"key":"ERROR","doc_count":5}]},
                 "nodes":{"buckets":[{"key":"node-1","doc_count":3},{"key":"node-2","doc_count":2}]},
                 "trend":{"buckets":[{"key":1792000000000,"doc_count":2},{"key":1792086400000,"doc_count":3}]},
                 "sample":{"hits":{"hits":[{"_source":{"message":"ERP call failed"}}]}}}]}}}
                """;
        List<ErrorLogSource.Group> groups = new OpenSearchErrorLogSource(null, props)
                .parse(new ObjectMapper().readTree(json));

        assertThat(groups).hasSize(1);
        ErrorLogSource.Group g = groups.get(0);
        assertThat(g.eventCode()).isEqualTo("CTN-INT-0102");
        assertThat(g.occurrences()).isEqualTo(5);
        assertThat(g.level()).isEqualTo("ERROR");
        assertThat(g.label()).isEqualTo("ERP call failed");
        assertThat(g.nodes()).containsExactly("node-1", "node-2");
        assertThat(g.trend()).extracting(ErrorLogSource.Trend::count).containsExactly(2L, 3L);
        assertThat(g.firstSeenAt()).isEqualTo(Instant.ofEpochMilli(1792000000000L));
        assertThat(g.runbook()).isNull();
    }

    @Test
    void emptyResponseGivesNoGroups() throws Exception {
        assertThat(new OpenSearchErrorLogSource(null, props).parse(new ObjectMapper().readTree("{}"))).isEmpty();
    }
}
