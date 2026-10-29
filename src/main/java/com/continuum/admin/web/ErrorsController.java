package com.continuum.admin.web;

import com.continuum.admin.AlertRuleStore;
import com.continuum.admin.ErrorLogSource;
import gen.admin.ops.api.AlertsApi;
import gen.admin.ops.model.AlertRule;
import gen.admin.ops.model.ErrorGroup;
import gen.admin.ops.model.ErrorGroupTrendInner;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Error groups by event code (contract {@code admin/operations.yaml}, {@code GET /errors}), read from the
 * technical log store through {@link ErrorLogSource}. It also implements the alert rule
 * CRUD (delegated to {@link AlertRulesHandler}) because both operations belong to the generated {@code AlertsApi}.
 */
@RestController
@RequestMapping("/api/admin")
public class ErrorsController implements AlertsApi {

    private static final String MANAGE = "@authz.can('admin.alerts.manage')";

    private final ErrorLogSource source;
    private final AlertRulesHandler alertRules;

    public ErrorsController(ErrorLogSource source, AlertRuleStore alertRuleStore) {
        this.source = source;
        this.alertRules = new AlertRulesHandler(alertRuleStore);
    }

    @Override
    @PreAuthorize(MANAGE)
    public ResponseEntity<List<AlertRule>> listAlertRules() {
        return alertRules.list();
    }

    @Override
    @PreAuthorize(MANAGE)
    public ResponseEntity<AlertRule> createAlertRule(AlertRule alertRule) {
        return alertRules.create(alertRule);
    }

    @Override
    @PreAuthorize(MANAGE)
    public ResponseEntity<AlertRule> updateAlertRule(UUID ruleId, AlertRule alertRule) {
        return alertRules.update(ruleId, alertRule);
    }

    @Override
    @PreAuthorize(MANAGE)
    public ResponseEntity<Void> deleteAlertRule(UUID ruleId) {
        return alertRules.delete(ruleId);
    }

    @Override
    @PreAuthorize("@authz.can('admin.logs.tech.read')")
    public ResponseEntity<List<ErrorGroup>> listErrorGroups(LocalDate from, LocalDate to) {
        Instant fromInstant = from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC);
        // "to" is inclusive in the contract: the exclusive bound is the start of the next day.
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return ResponseEntity.ok(source.groupByEventCode(fromInstant, toInstant).stream()
                .map(ErrorsController::map).toList());
    }

    private static ErrorGroup map(ErrorLogSource.Group g) {
        ErrorGroup out = new ErrorGroup().eventCode(g.eventCode()).label(g.label())
                .occurrences((int) g.occurrences()).firstSeenAt(utc(g.firstSeenAt()))
                .lastSeenAt(utc(g.lastSeenAt())).nodes(g.nodes()).runbook(g.runbook());
        if (g.level() != null) {
            try {
                out.level(ErrorGroup.LevelEnum.fromValue(g.level()));
            } catch (IllegalArgumentException ignored) {
                // a level outside the contract enum is left unset
            }
        }
        out.trend(g.trend().stream()
                .map(t -> new ErrorGroupTrendInner().at(utc(t.at())).count((int) t.count())).toList());
        return out;
    }

    private static OffsetDateTime utc(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
