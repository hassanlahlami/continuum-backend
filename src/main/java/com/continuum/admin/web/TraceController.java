package com.continuum.admin.web;

import com.continuum.admin.TraceSource;
import gen.admin.ops.api.LogsApi;
import gen.admin.ops.model.Trace;
import gen.admin.ops.model.TraceSpansInner;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** End-to-end view of one request (contract {@code admin/operations.yaml}, {@code GET /traces/{traceId}}, OPS-09). */
@RestController
@RequestMapping("/api/admin")
public class TraceController implements LogsApi {

    private final TraceSource source;

    public TraceController(TraceSource source) {
        this.source = source;
    }

    @Override
    @PreAuthorize("@authz.can('admin.logs.tech.read')")
    public ResponseEntity<Trace> getTrace(String traceId) {
        return ResponseEntity.ok(source.find(traceId).map(TraceController::map)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown trace")));
    }

    private static Trace map(TraceSource.Trace t) {
        Trace out = new Trace().traceId(t.traceId()).startedAt(t.startedAt().atOffset(ZoneOffset.UTC))
                .durationMs((int) t.durationMs())
                .outcome(t.error() ? Trace.OutcomeEnum.ERROR : Trace.OutcomeEnum.SUCCESS)
                .logs(t.logs());
        t.spans().forEach(s -> out.addSpansItem(new TraceSpansInner().spanId(s.spanId())
                .parentSpanId(s.parentSpanId()).service(s.service()).name(s.name())
                .startOffsetMs((int) s.startOffsetMs()).durationMs((int) s.durationMs())
                .status(s.error() ? TraceSpansInner.StatusEnum.ERROR : TraceSpansInner.StatusEnum.OK)
                .eventCode(s.eventCode())));
        return out;
    }
}
