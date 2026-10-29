package com.continuum.admin.web;

import com.continuum.admin.AlertRuleStore;
import com.continuum.admin.AlertRuleStore.Rule;
import gen.admin.ops.model.AlertRule;
import gen.admin.ops.model.AvailableAction;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * Alert rules CRUD logic behind the {@code alerts}-tag endpoints of {@link ErrorsController} (the generated {@code AlertsApi} is implemented once).
 * Toggling a rule is a {@code PUT} that changes {@code enabled}; an omitted {@code enabled} means enabled on create and
 * unchanged on update.
 */
class AlertRulesHandler {

    static final String ACTION_EDIT = "edit";
    static final String ACTION_DELETE = "delete";

    private final AlertRuleStore store;

    AlertRulesHandler(AlertRuleStore store) {
        this.store = store;
    }

    ResponseEntity<List<AlertRule>> list() {
        return ResponseEntity.ok(store.list().stream().map(AlertRulesHandler::toModel).toList());
    }

    ResponseEntity<AlertRule> create(AlertRule body) {
        Jwt jwt = currentJwt();
        Rule rule = toRule(body, body.getEnabled() == null || body.getEnabled());
        Rule created = store.create(rule, claim(jwt, "tenant_id"), claim(jwt, "continuum_user_id"));
        return ResponseEntity.status(HttpStatus.CREATED).body(toModel(created));
    }

    ResponseEntity<AlertRule> update(UUID ruleId, AlertRule body) {
        Jwt jwt = currentJwt();
        boolean enabled = body.getEnabled() != null ? body.getEnabled()
                : store.list().stream().filter(r -> r.id().equals(ruleId)).findFirst()
                        .map(Rule::enabled).orElseThrow(AlertRulesHandler::notFound);
        return store.update(ruleId, toRule(body, enabled), claim(jwt, "tenant_id"), claim(jwt, "continuum_user_id"))
                .map(r -> ResponseEntity.ok(toModel(r)))
                .orElseThrow(AlertRulesHandler::notFound);
    }

    ResponseEntity<Void> delete(UUID ruleId) {
        Jwt jwt = currentJwt();
        if (!store.delete(ruleId, claim(jwt, "tenant_id"), claim(jwt, "continuum_user_id"))) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    private static Rule toRule(AlertRule b, boolean enabled) {
        List<String> channels = b.getChannels() == null ? List.of()
                : b.getChannels().stream().map(AlertRule.ChannelsEnum::getValue).toList();
        Rule r = new Rule(null, b.getLabel(), b.getEventCode(), b.getThreshold() == null ? 0 : b.getThreshold(),
                b.getWindow(), channels, enabled);
        validate(r);
        return r;
    }

    private static void validate(Rule r) {
        if (r.label() == null || r.label().isBlank() || r.eventCode() == null || r.eventCode().isBlank()) {
            throw unprocessable("label and eventCode are required");
        }
        if (r.threshold() < 1) {
            throw unprocessable("threshold must be >= 1");
        }
        if (r.channels().isEmpty()) {
            throw unprocessable("at least one channel is required");
        }
        Duration window;
        try {
            window = Duration.parse(r.window());
        } catch (DateTimeParseException | NullPointerException e) {
            throw unprocessable("window must be an ISO-8601 duration (e.g. PT10M)");
        }
        if (window.isZero() || window.isNegative()) {
            throw unprocessable("window must be a positive duration");
        }
    }

    private static AlertRule toModel(Rule r) {
        return new AlertRule(r.label(), r.eventCode(), r.threshold(), r.window(),
                r.channels().stream().map(AlertRule.ChannelsEnum::fromValue).toList())
                .id(r.id())
                .enabled(r.enabled())
                .availableActions(List.of(new AvailableAction(ACTION_EDIT, "Modifier"),
                        new AvailableAction(ACTION_DELETE, "Supprimer").style(AvailableAction.StyleEnum.DANGER)));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown alert rule");
    }

    private static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    private static UUID claim(Jwt jwt, String name) {
        return UUID.fromString(jwt.getClaimAsString(name));
    }

    private static Jwt currentJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No JWT in security context");
        }
        return jwt;
    }
}
