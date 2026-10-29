package com.continuum.admin;

import java.time.Instant;
import java.util.List;

/**
 * Read port over the technical error log store (ECS events, plan §10). Returns ERROR/WARN events already
 * grouped by event code; the store behind it (OpenSearch) is an adapter detail.
 */
public interface ErrorLogSource {

    /** {@code from} inclusive lower bound or null; {@code to} exclusive upper bound or null. */
    List<Group> groupByEventCode(Instant from, Instant to);

    /** A bucket of events sharing one event code. {@code runbook} is Markdown or null. */
    record Group(String eventCode, String label, String level, long occurrences, Instant firstSeenAt,
                 Instant lastSeenAt, List<String> nodes, List<Trend> trend, String runbook) {
    }

    record Trend(Instant at, long count) {
    }
}
