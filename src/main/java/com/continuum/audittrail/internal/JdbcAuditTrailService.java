package com.continuum.audittrail.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.audittrail.api.AuditVerification;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Journal d'audit chaîné et signé (plan §2.3), PostgreSQL.
 *
 * <p>Chaque entrée reçoit un {@code seq} croissant par tenant ; son {@code entry_hash} est un
 * HMAC-SHA256 de ses champs concaténés au {@code prev_hash} (hachage de l'entrée précédente).
 * Modifier une entrée casse sa signature <em>et</em> toutes les suivantes → altération détectable.
 *
 * <p>Le {@code payload} est stocké verbatim (TEXT) pour que la vérification recompose exactement
 * l'empreinte. La clé HMAC provient de l'environnement (Vault, §4) — injectée au constructeur.
 *
 * <p>Limite connue : l'attribution du {@code seq} suppose une écriture sérialisée par tenant
 * (verrou/transaction à ajouter côté service applicatif ; hors périmètre de cette brique).
 */
public class JdbcAuditTrailService implements AuditTrailService {

    /** Séparateur d'unités (US 0x1F, non ambigu) entre champs du message signé. */
    private static final String SEP = String.valueOf((char) 0x1F);
    private static final String HMAC_ALGO = "HmacSHA256";

    private final JdbcTemplate jdbc;
    private final byte[] hmacKey;

    public JdbcAuditTrailService(DataSource dataSource, byte[] hmacKey) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.hmacKey = hmacKey.clone();
    }

    @Override
    public long append(AuditEntry entry) {
        long seq = 1 + jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM audit_event WHERE tenant_id = ?",
                Long.class, entry.tenantId());
        String prevHash = seq == 1 ? null : jdbc.queryForObject(
                "SELECT entry_hash FROM audit_event WHERE tenant_id = ? AND seq = ?",
                String.class, entry.tenantId(), seq - 1);

        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String entryHash = hash(seq, occurredAt, entry.actorUserId(), entry.action(),
                entry.objectType(), entry.objectId(), entry.payload(), prevHash);

        jdbc.update("""
                INSERT INTO audit_event
                  (id, tenant_id, seq, occurred_at, actor_user_id, action, object_type, object_id,
                   payload, prev_hash, entry_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), entry.tenantId(), seq, occurredAt.atOffset(ZoneOffset.UTC),
                entry.actorUserId(), entry.action(), entry.objectType(), entry.objectId(),
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
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("object_type"),
                        rs.getString("object_id"),
                        rs.getString("payload"),
                        rs.getString("prev_hash"),
                        rs.getString("entry_hash")),
                tenantId);

        String runningPrev = null;
        for (Row r : rows) {
            String expected = hash(r.seq, r.occurredAt, r.actor, r.action, r.objectType, r.objectId,
                    r.payload, runningPrev);
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
                """, Long.class, tenantId, actorUserId, action, objectType, objectId);
        return count != null && count > 0;
    }

    private String hash(long seq, Instant occurredAt, UUID actor, String action, String objectType,
            String objectId, String payload, String prevHash) {
        String canonical = String.join(SEP,
                Long.toString(seq),
                Long.toString(micros(occurredAt)),
                nullToEmpty(actor == null ? null : actor.toString()),
                nullToEmpty(action),
                nullToEmpty(objectType),
                nullToEmpty(objectId),
                nullToEmpty(payload),
                nullToEmpty(prevHash));
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGO));
            return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Calcul HMAC impossible", e);
        }
    }

    private static long micros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private record Row(long seq, Instant occurredAt, UUID actor, String action, String objectType,
                       String objectId, String payload, String prevHash, String entryHash) {
    }
}
