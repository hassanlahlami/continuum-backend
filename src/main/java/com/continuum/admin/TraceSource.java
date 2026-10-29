package com.continuum.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read port over the technical log store: every event of one request, reassembled as a trace (plan §10). */
public interface TraceSource {

    /** Empty when no event carries this trace id. */
    Optional<Trace> find(String traceId);

    record Trace(String traceId, Instant startedAt, long durationMs, boolean error, List<Span> spans,
                 List<Map<String, Object>> logs) {
    }

    /** {@code parentSpanId}, {@code service}, {@code name}, {@code eventCode} may be null. */
    record Span(String spanId, String parentSpanId, String service, String name, long startOffsetMs,
                long durationMs, boolean error, String eventCode) {
    }
}
