package com.continuum.iam.internal;

import com.continuum.iam.api.DirectoryFederation;
import com.continuum.iam.api.DirectorySyncService;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link DirectorySyncService} on {@code platform.directory_sync_state}, PostgreSQL and <strong>Oracle</strong> (D4).
 * The RUNNING claim is a single conditional {@code UPDATE}, so concurrent triggers and several instances never run
 * the same source twice.
 */
public class JdbcDirectorySyncService implements DirectorySyncService {

    private static final Logger log = LoggerFactory.getLogger(JdbcDirectorySyncService.class);
    private static final int MAX_ERROR = 1000;

    private final JdbcTemplate jdbc;
    private final boolean oracle;
    private final DirectoryFederation federation;
    private final Executor executor;
    private final String sourceCode;

    public JdbcDirectorySyncService(DataSource dataSource, boolean oracle, DirectoryFederation federation,
                                    Executor executor, String sourceCode) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.oracle = oracle;
        this.federation = federation;
        this.executor = executor;
        this.sourceCode = sourceCode == null ? "" : sourceCode.trim();
    }

    @Override
    public boolean configured() {
        return !sourceCode.isEmpty();
    }

    @Override
    public Optional<SyncState> status(UUID tenantId) {
        if (!configured()) {
            return Optional.empty();
        }
        return find(tenantId).filter(s -> s.state() != State.NEVER);
    }

    @Override
    public SyncState trigger(UUID tenantId) {
        Claim claim = claim(tenantId);
        if (claim.owned()) {
            executor.execute(() -> execute(tenantId));
        }
        return claim.state();
    }

    @Override
    public SyncState runNow(UUID tenantId) {
        Claim claim = claim(tenantId);
        return claim.owned() ? execute(tenantId) : claim.state();
    }

    private record Claim(SyncState state, boolean owned) { }

    private Claim claim(UUID tenantId) {
        if (!configured()) {
            throw new IllegalStateException("No directory source configured (continuum.directory-sync.source-code)");
        }
        ensureRow(tenantId);
        int updated = jdbc.update("UPDATE platform.directory_sync_state SET status = 'RUNNING', last_error = NULL,"
                        + " version = version + 1 WHERE tenant_id = ? AND source_code = ? AND status <> 'RUNNING'",
                key(tenantId), sourceCode);
        return new Claim(find(tenantId).orElseThrow(), updated == 1);
    }

    private SyncState execute(UUID tenantId) {
        try {
            int failed = federation.fullSync(sourceCode);
            finish(tenantId, "SUCCESS", Timestamp.from(Instant.now()),
                    failed > 0 ? failed + " user(s) reported as failed by the provider" : null);
        } catch (RuntimeException e) {
            log.warn("Directory synchronisation failed for tenant {}: {}", tenantId, e.getMessage());
            finish(tenantId, "FAILED", null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return find(tenantId).orElseThrow();
    }

    private void finish(UUID tenantId, String status, Timestamp lastSyncAt, String error) {
        String err = error == null || error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
        // Two statements rather than COALESCE(?, ...): Oracle cannot bind an untyped NULL there (ORA-17004).
        if (lastSyncAt == null) {
            jdbc.update("UPDATE platform.directory_sync_state SET status = ?, last_error = ?, version = version + 1"
                    + " WHERE tenant_id = ? AND source_code = ?", status, err, key(tenantId), sourceCode);
        } else {
            jdbc.update("UPDATE platform.directory_sync_state SET status = ?, last_error = ?, last_sync_at = ?,"
                            + " version = version + 1 WHERE tenant_id = ? AND source_code = ?",
                    status, err, lastSyncAt, key(tenantId), sourceCode);
        }
    }

    private void ensureRow(UUID tenantId) {
        if (find(tenantId).isPresent()) {
            return;
        }
        try {
            jdbc.update("INSERT INTO platform.directory_sync_state (id, tenant_id, source_code) VALUES (?, ?, ?)",
                    key(UUID.randomUUID()), key(tenantId), sourceCode);
        } catch (DuplicateKeyException ignored) {
            // created concurrently by another instance
        }
    }

    private Optional<SyncState> find(UUID tenantId) {
        List<SyncState> rows = jdbc.query("SELECT id, source_code, status, last_sync_at, last_error, version"
                        + " FROM platform.directory_sync_state WHERE tenant_id = ? AND source_code = ?",
                (rs, i) -> map(rs), key(tenantId), sourceCode);
        return rows.stream().findFirst();
    }

    private SyncState map(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("last_sync_at");
        return new SyncState(uuid(rs.getObject("id")), rs.getString("source_code"),
                State.valueOf(rs.getString("status")), ts == null ? null : ts.toInstant(),
                rs.getString("last_error"), rs.getLong("version"));
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
        ByteBuffer b = ByteBuffer.wrap((byte[]) o);
        return new UUID(b.getLong(), b.getLong());
    }
}
