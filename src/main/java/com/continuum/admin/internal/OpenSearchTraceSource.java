package com.continuum.admin.internal;

import com.continuum.admin.TraceSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.client.RestClient;

/**
 * {@link TraceSource} over the OpenSearch search API: fetches the events of one trace id in time order and
 * derives the spans from their span ids. Field names and the event cap come from {@link ErrorLogProperties}.
 */
public class OpenSearchTraceSource implements TraceSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;
    private final ErrorLogProperties props;

    public OpenSearchTraceSource(RestClient http, ErrorLogProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public Optional<Trace> find(String traceId) {
        Map<String, Object> body = Map.of("size", props.maxTraceEvents(),
                "query", Map.of("term", Map.of(props.traceIdField(), traceId)),
                "sort", List.of(Map.of(props.timestampField(), "asc")));
        JsonNode response = http.post().uri("/{index}/_search", props.indexPattern())
                .body(body).retrieve().body(JsonNode.class);
        return parse(traceId, response);
    }

    Optional<Trace> parse(String traceId, JsonNode response) {
        List<Map<String, Object>> logs = new ArrayList<>();
        Map<String, SpanAcc> spans = new LinkedHashMap<>();
        Instant first = null;
        Instant last = null;
        boolean error = false;
        if (response != null) {
            for (JsonNode hit : response.path("hits").path("hits")) {
                JsonNode src = hit.path("_source");
                Instant at = parseInstant(text(src, props.timestampField()));
                if (at == null) {
                    continue;
                }
                logs.add(MAPPER.convertValue(src, new TypeReference<Map<String, Object>>() { }));
                first = first == null || at.isBefore(first) ? at : first;
                last = last == null || at.isAfter(last) ? at : last;
                boolean isError = "ERROR".equalsIgnoreCase(text(src, props.levelField()));
                error |= isError;
                String spanId = text(src, props.spanIdField());
                if (spanId != null) {
                    spans.computeIfAbsent(spanId, SpanAcc::new).add(src, at, isError, props);
                }
            }
        }
        if (logs.isEmpty()) {
            return Optional.empty();
        }
        Instant start = first;
        List<Span> out = spans.values().stream().map(a -> a.toSpan(start)).toList();
        return Optional.of(new Trace(traceId, start, last.toEpochMilli() - start.toEpochMilli(), error, out, logs));
    }

    private static final class SpanAcc {
        final String id;
        String parent;
        String service;
        String name;
        String eventCode;
        Instant from;
        Instant to;
        boolean error;

        SpanAcc(String id) {
            this.id = id;
        }

        void add(JsonNode src, Instant at, boolean isError, ErrorLogProperties p) {
            parent = parent != null ? parent : text(src, p.parentSpanIdField());
            service = service != null ? service : text(src, p.serviceField());
            name = name != null ? name : text(src, p.spanNameField());
            String code = text(src, p.eventCodeField());
            if (code != null && (isError || eventCode == null)) {
                eventCode = code;
            }
            from = from == null || at.isBefore(from) ? at : from;
            to = to == null || at.isAfter(to) ? at : to;
            error |= isError;
        }

        Span toSpan(Instant traceStart) {
            return new Span(id, parent, service, name, from.toEpochMilli() - traceStart.toEpochMilli(),
                    to.toEpochMilli() - from.toEpochMilli(), error, eventCode);
        }
    }

    private static Instant parseInstant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Reads a flat dotted key first (ECS flattened), then walks the nested objects. */
    private static String text(JsonNode source, String dottedField) {
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
