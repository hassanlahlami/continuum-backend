package com.continuum.admin.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code continuum.error-log.*}: where technical error events are read from. Blank {@code baseUrl} = none. */
@ConfigurationProperties("continuum.error-log")
public record ErrorLogProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("continuum-logs-*") String indexPattern,
        @DefaultValue("@timestamp") String timestampField,
        @DefaultValue("log.level") String levelField,
        @DefaultValue("event.code") String eventCodeField,
        @DefaultValue("message") String messageField,
        @DefaultValue("service.node.name") String nodeField,
        @DefaultValue({"ERROR", "WARN"}) List<String> levels,
        @DefaultValue("1d") String trendInterval,
        @DefaultValue("200") int maxGroups,
        @DefaultValue("20") int maxNodes,
        @DefaultValue("trace.id") String traceIdField,
        @DefaultValue("span.id") String spanIdField,
        @DefaultValue("span.parent.id") String parentSpanIdField,
        @DefaultValue("service.name") String serviceField,
        @DefaultValue("event.action") String spanNameField,
        @DefaultValue("500") int maxTraceEvents) {
}
