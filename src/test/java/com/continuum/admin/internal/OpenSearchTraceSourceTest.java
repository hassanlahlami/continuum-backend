package com.continuum.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.admin.TraceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reassembly of OpenSearch hits into a trace with spans. */
class OpenSearchTraceSourceTest {

    private final ErrorLogProperties props = new ErrorLogProperties("", "", "", "continuum-logs-*", "@timestamp",
            "log.level", "event.code", "message", "service.node.name", List.of("ERROR", "WARN"), "1d", 200, 20,
            "trace.id", "span.id", "span.parent.id", "service.name", "event.action", 500);

    @Test
    void buildsSpansAndOutcome() throws Exception {
        String json = """
                {"hits":{"hits":[
                 {"_source":{"@timestamp":"2026-10-28T10:00:00.000Z","log.level":"INFO","span.id":"a","service.name":"continuum-backend","event.action":"POST /x","message":"start"}},
                 {"_source":{"@timestamp":"2026-10-28T10:00:00.050Z","log":{"level":"ERROR"},"span":{"id":"b","parent":{"id":"a"}},"service":{"name":"erp"},"event":{"code":"CTN-INT-0102","action":"call"},"message":"boom"}},
                 {"_source":{"@timestamp":"2026-10-28T10:00:00.120Z","log.level":"INFO","span.id":"a","message":"end"}}]}}
                """;
        TraceSource.Trace t = new OpenSearchTraceSource(null, props).parse("t", new ObjectMapper().readTree(json))
                .orElseThrow();

        assertThat(t.durationMs()).isEqualTo(120);
        assertThat(t.error()).isTrue();
        assertThat(t.logs()).hasSize(3);
        assertThat(t.spans()).hasSize(2);
        TraceSource.Span a = t.spans().get(0);
        assertThat(a.durationMs()).isEqualTo(120);
        assertThat(a.error()).isFalse();
        TraceSource.Span b = t.spans().get(1);
        assertThat(b.parentSpanId()).isEqualTo("a");
        assertThat(b.startOffsetMs()).isEqualTo(50);
        assertThat(b.eventCode()).isEqualTo("CTN-INT-0102");
        assertThat(b.service()).isEqualTo("erp");
    }

    @Test
    void noHitsIsEmpty() throws Exception {
        assertThat(new OpenSearchTraceSource(null, props)
                .parse("t", new ObjectMapper().readTree("{\"hits\":{\"hits\":[]}}"))).isEmpty();
    }
}
