package com.continuum.audittrail.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditRecord;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.audittrail.api.AuditVerification;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Journal d'audit chaîné et signé (plan §2.3), <strong>dialecte Oracle</strong> (D4).
 *
 * <p>Même logique et <strong>même empreinte</strong> que {@link JdbcAuditTrailService} (signature via
 * {@link AuditHmac}), mais liaison JDBC adaptée à Oracle : UUID ↔ {@code RAW(16)} (16 octets
 * big-endian), {@code payload} en {@code CLOB}, horodatage en {@code TIMESTAMP WITH TIME ZONE}.
 */
public class OracleAuditTrailService implements AuditTrailService {

    private final JdbcTemplate jdbc;
    private final byte[] hmacKey;

    public OracleAuditTrailService(DataSource dataSource, byte[] hmacKey) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.hmacKey = hmacKey.clone();
    }

    @Override
    public long append(AuditEntry entry) {
        long seq = 1 + jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM audit_event WHERE tenant_id = ?",
                Long.class, raw(entry.tenantId()));
        String prevHash = seq == 1 ? null : jdbc.queryForObject(
                "SELECT entry_hash FROM audit_event WHERE tenant_id = ? AND seq = ?",
                String.class, raw(entry.tenantId()), seq - 1);

        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String entryHash = AuditHmac.hash(hmacKey, seq, occurredAt, entry.actorUserId(), entry.action(),
                entry.objectType(), entry.objectId(), entry.payload(), prevHash);

        jdbc.update("""
                INSERT INTO audit_event
                  (id, tenant_id, seq, occurred_at, actor_user_id, action, object_type, object_id,
                   payload, prev_hash, entry_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                raw(UUID.randomUUID()), raw(entry.tenantId()), seq, occurredAt.atOffset(ZoneOffset.UTC),
                raw(entry.actorUserId()), entry.action(), entry.objectType(), entry.objectId(),
                entry.payload(), prevHash, entryHash);
        return seq;
    }

    @Override
    public AuditVerification verify(UUID tenantId) {
        List<Row> rows = jdbc.query("""
                SELECT seq, occurred_at, actor_user_id, action, object_type, object_id,
                       payload, prev_hash, entry_hash
                FROM audit_event WHERE tenant_id = ? ORDER BY seq
                """,
                (rs, i) -> new Row(
                        rs.getLong("seq"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        uuid(rs.getBytes("actor_user_id")),
                        rs.getString("action"),
                        rs.getString("object_type"),
                        rs.getString("object_id"),
                        rs.getString("payload"),
                        trim(rs.getString("prev_hash")),
                        trim(rs.getString("entry_hash"))),
                raw(tenantId));

        String runningPrev = null;
        for (Row r : rows) {
            String expected = AuditHmac.hash(hmacKey, r.seq, r.occurredAt, r.actor, r.action,
                    r.objectType, r.objectId, r.payload, runningPrev);
            boolean prevMatches = runningPrev == null ? r.prevHash == null : runningPrev.equals(r.prevHash);
            if (!prevMatches || !expected.equals(r.entryHash)) {
                return new AuditVerification(false, r.seq, rows.size());
            }
            runningPrev = r.entryHash;
        }
        return new AuditVerification(true, null, rows.size());
    }

    @Override
    public boolean hasActed(UUID tenantId, UUID actorUserId, String action, String objectType, String objectId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                WHERE tenant_id = ? AND actor_user_id = ? AND action = ? AND object_type = ? AND object_id = ?
                """, Long.class, raw(tenantId), raw(actorUserId), action, objectType, objectId);
        return count != null && count > 0;
    }

    @Override
    public List<AuditRecord> export(UUID tenantId) {
        return jdbc.query("""
                SELECT seq, occurred_at, actor_user_id, action, object_type, object_id, payload, entry_hash
                FROM audit_event WHERE tenant_id = ? ORDER BY seq
                """,
                (rs, i) -> new AuditRecord(
                        rs.getLong("seq"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        uuid(rs.getBytes("actor_user_id")),
                        rs.getString("action"),
                        rs.getString("object_type"),
                        rs.getString("object_id"),
                        rs.getString("payload"),
                        trim(rs.getString("entry_hash"))),
                raw(tenantId));
    }

    /** CHAR(64) Oracle est complété d'espaces : on retire le remplissage (les empreintes font 64 hex). */
    private static String trim(String s) {
        return s == null ? null : s.strip();
    }

    /** RAW(16) → UUID (16 octets big-endian). */
    static UUID uuid(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }

    /** UUID → RAW(16) (16 octets big-endian) pour la liaison JDBC Oracle. */
    static byte[] raw(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private record Row(long seq, Instant occurredAt, UUID actor, String action, String objectType,
                       String objectId, String payload, String prevHash, String entryHash) {
    }
}
