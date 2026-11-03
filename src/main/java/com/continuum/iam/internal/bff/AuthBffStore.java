package com.continuum.iam.internal.bff;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence of the BFF (platform scope, ADR 0012: written through the platform DataSource): sessions, login
 * attempt counters and parameters. One implementation for both dialects; only the UUID binding differs
 * (PostgreSQL {@code UUID}, Oracle {@code RAW(16)}).
 */
public class AuthBffStore {

    public record SessionRow(String idHash, String app, UUID tenantId, UUID userId, byte[] refreshTokenEnc, String acr,
            OffsetDateTime expiresAt, OffsetDateTime idleExpiresAt) {
    }

    public record AttemptRow(int count, OffsetDateTime windowStart, OffsetDateTime blockedUntil) {
    }

    private final JdbcTemplate jdbc;
    private final boolean oracle;

    public AuthBffStore(DataSource platformDataSource, boolean oracle) {
        this.jdbc = new JdbcTemplate(platformDataSource);
        this.oracle = oracle;
    }

    // ----- tenants & parameters

    public Optional<UUID> findActiveTenant(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT id FROM platform.tenant WHERE code = ? AND active = " + (oracle ? "1" : "TRUE"),
                (rs, i) -> uuid(rs, "id"), code).stream().findFirst();
    }

    /** Effective parameter: tenant override, else installation default (pack), else empty. */
    public Optional<String> param(UUID tenantId, String key) {
        List<String> v = jdbc.query("SELECT param_value FROM platform.tenant_param WHERE tenant_id = ? AND param_key = ?",
                (rs, i) -> rs.getString(1), bind(tenantId), key);
        if (v.isEmpty()) {
            v = jdbc.query("SELECT param_value FROM platform.param_default WHERE param_key = ?",
                    (rs, i) -> rs.getString(1), key);
        }
        return v.stream().findFirst();
    }

    public Optional<String> username(UUID tenantId, UUID userId) {
        return jdbc.query("SELECT username FROM platform.user_account WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString(1), bind(tenantId), bind(userId)).stream().findFirst();
    }

    // ----- sessions

    public void createSession(SessionRow s) {
        jdbc.update("INSERT INTO platform.auth_session (id_hash, app, tenant_id, user_id, refresh_token_enc,"
                + " acr, expires_at, idle_expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                s.idHash(), s.app(), bind(s.tenantId()), bind(s.userId()), s.refreshTokenEnc(), s.acr(),
                s.expiresAt(), s.idleExpiresAt());
    }

    public Optional<SessionRow> findLiveSession(String idHash, OffsetDateTime now) {
        return jdbc.query("SELECT id_hash, app, tenant_id, user_id, refresh_token_enc, acr, expires_at, idle_expires_at"
                + " FROM platform.auth_session WHERE id_hash = ? AND revoked_at IS NULL"
                + " AND expires_at > ? AND idle_expires_at > ?",
                (rs, i) -> new SessionRow(rs.getString("id_hash"), rs.getString("app"), uuid(rs, "tenant_id"), uuid(rs, "user_id"),
                        rs.getBytes("refresh_token_enc"), rs.getString("acr"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("idle_expires_at", OffsetDateTime.class)),
                idHash, now, now).stream().findFirst();
    }

    public void slide(String idHash, OffsetDateTime idleExpiresAt) {
        jdbc.update("UPDATE platform.auth_session SET idle_expires_at = ? WHERE id_hash = ? AND revoked_at IS NULL",
                idleExpiresAt, idHash);
    }

    public void updateRefreshToken(String idHash, byte[] refreshTokenEnc) {
        jdbc.update("UPDATE platform.auth_session SET refresh_token_enc = ? WHERE id_hash = ? AND revoked_at IS NULL",
                refreshTokenEnc, idHash);
    }

    /** Step-up: the existing session takes the new level and the refresh token of the upgraded SSO session. */
    public void updateStepUp(String idHash, byte[] refreshTokenEnc, String acr) {
        jdbc.update("UPDATE platform.auth_session SET refresh_token_enc = ?, acr = ? WHERE id_hash = ?"
                + " AND revoked_at IS NULL", refreshTokenEnc, acr, idHash);
    }

    public void revoke(String idHash, OffsetDateTime now) {
        jdbc.update("UPDATE platform.auth_session SET revoked_at = ? WHERE id_hash = ? AND revoked_at IS NULL",
                now, idHash);
    }

    // ----- login attempt counters

    public Optional<AttemptRow> attempt(String scopeType, String scopeKey) {
        return jdbc.query("SELECT attempt_count, window_start, blocked_until FROM platform.auth_login_attempt"
                + " WHERE scope_type = ? AND scope_key = ?",
                (rs, i) -> new AttemptRow(rs.getInt(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class)),
                scopeType, scopeKey).stream().findFirst();
    }

    public void saveAttempt(String scopeType, String scopeKey, UUID tenantId, AttemptRow row) {
        int n = jdbc.update("UPDATE platform.auth_login_attempt SET attempt_count = ?, window_start = ?,"
                + " blocked_until = ? WHERE scope_type = ? AND scope_key = ?",
                row.count(), row.windowStart(), row.blockedUntil(), scopeType, scopeKey);
        if (n == 0) {
            jdbc.update("INSERT INTO platform.auth_login_attempt (scope_type, scope_key, tenant_id, attempt_count,"
                    + " window_start, blocked_until) VALUES (?, ?, ?, ?, ?, ?)",
                    scopeType, scopeKey, tenantId == null ? null : bind(tenantId), row.count(), row.windowStart(),
                    row.blockedUntil());
        }
    }

    public void clearAttempt(String scopeType, String scopeKey) {
        jdbc.update("DELETE FROM platform.auth_login_attempt WHERE scope_type = ? AND scope_key = ?",
                scopeType, scopeKey);
    }

    // ----- UUID binding

    private Object bind(UUID id) {
        if (!oracle) {
            return id;
        }
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private UUID uuid(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        if (!oracle) {
            return rs.getObject(col, UUID.class);
        }
        ByteBuffer bb = ByteBuffer.wrap(rs.getBytes(col));
        return new UUID(bb.getLong(), bb.getLong());
    }
}
