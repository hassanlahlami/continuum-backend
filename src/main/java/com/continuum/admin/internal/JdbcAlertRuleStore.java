package com.continuum.admin.internal;

import com.continuum.admin.AlertRuleStore;
import com.continuum.PlatformWriteConfig;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL and Oracle implementation of {@link AlertRuleStore}. Only the id/boolean binding differs
 * (UUID vs {@code RAW(16)}, boolean vs {@code NUMBER(1)}), so one class switches on {@code continuum.db.dialect}.
 */
@Component
class JdbcAlertRuleStore implements AlertRuleStore {

    private static final String FEATURE = "admin.alerts.manage";
    private static final String OBJECT = "ALERT_RULE";

    private final JdbcTemplate jdbc;
    private final AuditTrailService auditTrail;
    private final boolean oracle;

    JdbcAlertRuleStore(PlatformWriteConfig.PlatformWrite platform, AuditTrailService auditTrail,
                       @Value("${continuum.db.dialect:postgresql}") String dialect) {
        this.jdbc = new JdbcTemplate(platform.dataSource());
        this.auditTrail = auditTrail;
        this.oracle = "oracle".equalsIgnoreCase(dialect);
    }

    @Override
    public List<Rule> list() {
        Map<UUID, List<String>> channels = new LinkedHashMap<>();
        jdbc.query("SELECT alert_rule_id, channel FROM platform.alert_rule_channel ORDER BY channel",
                rs -> {
                    channels.computeIfAbsent(uuid(rs.getObject(1)), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return jdbc.query("SELECT id, label, event_code, threshold, window_iso, enabled"
                        + " FROM platform.alert_rule ORDER BY created_at, label",
                (rs, i) -> {
                    UUID id = uuid(rs.getObject("id"));
                    return new Rule(id, rs.getString("label"), rs.getString("event_code"),
                            rs.getInt("threshold"), rs.getString("window_iso"),
                            channels.getOrDefault(id, List.of()), rs.getBoolean("enabled"));
                });
    }

    @Override
    @Transactional
    public Rule create(Rule r, UUID actorTenantId, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.alert_rule(id, label, event_code, threshold, window_iso, enabled)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bind(id), r.label(), r.eventCode(), r.threshold(), r.window(), flag(r.enabled()));
        writeChannels(id, r.channels());
        Rule created = withId(id, r);
        audit(actorTenantId, actorUserId, "create", id, created);
        return created;
    }

    @Override
    @Transactional
    public Optional<Rule> update(UUID id, Rule r, UUID actorTenantId, UUID actorUserId) {
        int n = jdbc.update("UPDATE platform.alert_rule SET label = ?, event_code = ?, threshold = ?,"
                        + " window_iso = ?, enabled = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ?",
                r.label(), r.eventCode(), r.threshold(), r.window(), flag(r.enabled()), bind(id));
        if (n == 0) {
            return Optional.empty();
        }
        jdbc.update("DELETE FROM platform.alert_rule_channel WHERE alert_rule_id = ?", bind(id));
        writeChannels(id, r.channels());
        Rule updated = withId(id, r);
        audit(actorTenantId, actorUserId, "update", id, updated);
        return Optional.of(updated);
    }

    @Override
    @Transactional
    public boolean delete(UUID id, UUID actorTenantId, UUID actorUserId) {
        // ON DELETE CASCADE removes the channels.
        boolean deleted = jdbc.update("DELETE FROM platform.alert_rule WHERE id = ?", bind(id)) > 0;
        if (deleted) {
            auditTrail.append(new AuditEntry(actorTenantId, actorUserId, FEATURE, OBJECT, id.toString(),
                    "{\"action\":\"delete\"}"));
        }
        return deleted;
    }

    private static Rule withId(UUID id, Rule r) {
        return new Rule(id, r.label(), r.eventCode(), r.threshold(), r.window(),
                r.channels().stream().distinct().toList(), r.enabled());
    }

    private void writeChannels(UUID id, List<String> channels) {
        for (String ch : channels.stream().distinct().toList()) {
            jdbc.update("INSERT INTO platform.alert_rule_channel(alert_rule_id, channel) VALUES (?, ?)",
                    bind(id), ch);
        }
    }

    private void audit(UUID tenant, UUID user, String action, UUID id, Rule after) {
        String payload = "{\"action\":\"" + action + "\",\"eventCode\":\"" + after.eventCode().replace("\"", "'")
                + "\",\"threshold\":" + after.threshold() + ",\"enabled\":" + after.enabled() + "}";
        auditTrail.append(new AuditEntry(tenant, user, FEATURE, OBJECT, id.toString(), payload));
    }

    private Object bind(UUID id) {
        return oracle ? raw(id) : id;
    }

    private Object flag(boolean b) {
        return oracle ? (Object) (b ? 1 : 0) : (Object) b;
    }

    private static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(Object o) {
        if (o instanceof UUID u) {
            return u;
        }
        ByteBuffer bb = ByteBuffer.wrap((byte[]) o);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
