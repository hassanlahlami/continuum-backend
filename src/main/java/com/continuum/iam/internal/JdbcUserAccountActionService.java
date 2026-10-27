package com.continuum.iam.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.IdentityProviderAdmin;
import com.continuum.iam.api.UserAccountActionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implémentation de {@link UserAccountActionService} sur {@code platform.user_account}, pour PostgreSQL et
 * <strong>Oracle</strong> (D4) : seuls la liaison des UUID ({@code RAW(16)}) et le booléen diffèrent.
 * L'appel au fournisseur d'identité a lieu <em>après</em> la mise à jour : son échec annule la transaction.
 */
public class JdbcUserAccountActionService implements UserAccountActionService {

    private static final String FEATURE = "admin.user.manage";
    private static final String OBJECT = "USER";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final AuditTrailService auditTrail;
    private final IdentityProviderAdmin identity;
    private final boolean oracle;

    public JdbcUserAccountActionService(DataSource dataSource, AuditTrailService auditTrail,
                                        IdentityProviderAdmin identity, boolean oracle) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.auditTrail = auditTrail;
        this.identity = identity;
        this.oracle = oracle;
    }

    @Override
    @Transactional
    public UserAccountView perform(UserActionCommand c) {
        String action = c.actionCode();
        String targetStatus = switch (action) {
            case ACTION_LOCK -> STATUS_LOCKED;
            case ACTION_UNLOCK -> STATUS_ACTIVE;
            case ACTION_DISABLE -> STATUS_DISABLED;
            case ACTION_RESET_MFA, ACTION_CLOSE_SESSIONS -> null;
            default -> throw new IllegalArgumentException("Action de compte inconnue : " + action);
        };
        if (UserAccountActionService.requiresComment(action)
                && (c.comment() == null || c.comment().isBlank())) {
            throw new IllegalArgumentException("Un commentaire est obligatoire pour l'action « " + action + " »");
        }
        UserAccountView before = load(c.userId());
        boolean self = c.userId().equals(c.actorUserId());
        if (!UserAccountActionService.actionsFor(before.status(), before.mfaConfigured(), self).contains(action)) {
            throw new IllegalUserActionException(before.status(), action);
        }
        if (c.expectedVersion() != null && c.expectedVersion() != before.version()) {
            throw new StaleUserException(c.expectedVersion(), before.version());
        }
        String versionGuard = c.expectedVersion() == null ? "" : " AND version = ?";
        if (targetStatus != null) {
            update("UPDATE platform.user_account SET status = ?, version = version + 1 WHERE id = ?" + versionGuard,
                    c, before, targetStatus);
        } else if (ACTION_RESET_MFA.equals(action)) {
            update("UPDATE platform.user_account SET mfa_configured = " + (oracle ? "0" : "FALSE")
                    + ", version = version + 1 WHERE id = ?" + versionGuard, c, before, null);
            identity.resetMfa(c.userId());
        } else {
            identity.closeSessions(c.userId());
        }
        auditTrail.append(new AuditEntry(c.actorTenantId(), c.actorUserId(), FEATURE, OBJECT,
                c.userId().toString(),
                auditPayload(action, before.status(), targetStatus == null ? before.status() : targetStatus,
                        c.comment())));
        return load(c.userId());
    }

    private void update(String sql, UserActionCommand c, UserAccountView before, String status) {
        Object key = key(c.userId());
        Object[] args;
        if (status != null) {
            args = c.expectedVersion() == null ? new Object[] {status, key}
                    : new Object[] {status, key, c.expectedVersion()};
        } else {
            args = c.expectedVersion() == null ? new Object[] {key} : new Object[] {key, c.expectedVersion()};
        }
        if (jdbc.update(sql, args) == 0) {
            throw new StaleUserException(c.expectedVersion() == null ? -1 : c.expectedVersion(), before.version());
        }
    }

    private UserAccountView load(UUID id) {
        List<UserAccountView> rows = jdbc.query(
                "SELECT id, tenant_id, username, source, status, mfa_configured, last_login, version"
                        + " FROM platform.user_account WHERE id = ?",
                (rs, i) -> new UserAccountView(uuid(rs.getObject("id")), uuid(rs.getObject("tenant_id")),
                        rs.getString("username"), rs.getString("source"), rs.getString("status"),
                        rs.getBoolean("mfa_configured"),
                        rs.getTimestamp("last_login") == null ? null : rs.getTimestamp("last_login").toInstant(),
                        rs.getLong("version")),
                key(id));
        return rows.stream().findFirst().orElseThrow(() -> new UnknownUserException(id));
    }

    private Object key(UUID id) {
        if (!oracle) {
            return id;
        }
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

    private static String auditPayload(String action, String before, String after, String comment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("before", before);
        m.put("after", after);
        if (comment != null && !comment.isBlank()) {
            m.put("comment", comment);
        }
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
