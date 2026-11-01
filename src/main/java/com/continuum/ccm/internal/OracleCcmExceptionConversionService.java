package com.continuum.ccm.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.ccm.api.CcmExceptionConversionService;
import com.continuum.iam.api.AuthorizationService;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversion d'une exception CCM retenue en constat candidat (plan §5.3, D34) — implémentation
 * <strong>Oracle</strong> (D4), à parité avec {@link JdbcCcmExceptionConversionService}. Spécifique au
 * dialecte : UUID liés en {@code RAW(16)}, identifiant du constat généré côté application (pas de
 * {@code RETURNING}). Même décision serveur et journalisation signée (§2.3).
 */
public class OracleCcmExceptionConversionService implements CcmExceptionConversionService {

    private static final String OBJECT = "CCM_EXCEPTION";

    private final NamedParameterJdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditTrailService auditTrail;

    public OracleCcmExceptionConversionService(
            DataSource dataSource, AuthorizationService authorization, AuditTrailService auditTrail) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.authorization = authorization;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public ConversionResult convert(ConvertCommand command) {
        Exc exc = loadException(command.tenantId(), command.exceptionId());
        if (exc == null) {
            throw new ExceptionNotVisibleException();
        }
        if (!canConvert(command.tenantId(), command.actorUserId(), exc.orgUnitId)) {
            throw new ConvertNotPermittedException();
        }
        if (!"RETAINED".equals(exc.statusCode)) {
            throw new NotRetainedException(exc.statusCode);
        }
        if (exc.findingId != null) {
            throw new AlreadyConvertedException();
        }
        UUID missionUnit = missionUnit(command.tenantId(), command.missionId());
        if (missionUnit == null) {
            throw new MissionNotFoundException();
        }

        UUID findingId = UUID.randomUUID();
        MapSqlParameterSource insert = new MapSqlParameterSource()
                .addValue("id", raw(findingId))
                .addValue("tenant", raw(command.tenantId()))
                .addValue("mission", raw(command.missionId()))
                .addValue("unit", raw(missionUnit))
                .addValue("reference", exc.reference)
                .addValue("title", exc.title)
                .addValue("critCode", exc.criticalityCode)
                .addValue("critLabel", exc.criticalityLabel)
                .addValue("fact", exc.fact)
                .addValue("criterion", exc.criterion);
        jdbc.update("""
                INSERT INTO finding (id, tenant_id, mission_id, org_unit_id, reference, title,
                                     status_code, status_label, criticality_code, criticality_label, source,
                                     fact, criterion)
                VALUES (:id, :tenant, :mission, :unit, :reference, :title, 'OUVERT', 'Ouvert',
                        :critCode, :critLabel, 'CCM', :fact, :criterion)
                """, insert);

        jdbc.update("UPDATE ccm_exception SET finding_id = :finding, mission_id = :mission, version = version + 1"
                + " WHERE id = :id AND tenant_id = :tenant",
                new MapSqlParameterSource().addValue("finding", raw(findingId)).addValue("mission", raw(command.missionId()))
                        .addValue("id", raw(command.exceptionId())).addValue("tenant", raw(command.tenantId())));

        String payload = "{\"action\":\"convert\",\"findingId\":\"%s\",\"missionId\":\"%s\"}"
                .formatted(findingId, command.missionId());
        auditTrail.append(new AuditEntry(command.tenantId(), command.actorUserId(),
                FEATURE_CONVERT, OBJECT, command.exceptionId().toString(), payload));

        return new ConversionResult(findingId, command.missionId());
    }

    private boolean canConvert(UUID tenantId, UUID userId, UUID orgUnitId) {
        UUID scopeUnit = orgUnitId != null ? orgUnitId : rootUnit(tenantId);
        return scopeUnit != null && authorization.can(tenantId, userId, FEATURE_CONVERT, scopeUnit);
    }

    private UUID rootUnit(UUID tenantId) {
        List<byte[]> rows = jdbc.query(
                "SELECT id FROM org_unit WHERE tenant_id = :tenant AND parent_id IS NULL"
                + " ORDER BY id FETCH FIRST 1 ROW ONLY",
                new MapSqlParameterSource("tenant", raw(tenantId)), (rs, i) -> rs.getBytes("id"));
        return rows.isEmpty() ? null : uuid(rows.get(0));
    }

    private UUID missionUnit(UUID tenantId, UUID missionId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("mission", raw(missionId)).addValue("tenant", raw(tenantId));
        List<byte[]> rows = jdbc.query("SELECT org_unit_id FROM mission WHERE id = :mission AND tenant_id = :tenant",
                p, (rs, i) -> rs.getBytes("org_unit_id"));
        return rows.isEmpty() ? null : uuid(rows.get(0));
    }

    private Exc loadException(UUID tenantId, UUID exceptionId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", raw(exceptionId)).addValue("tenant", raw(tenantId));
        List<Exc> rows = jdbc.query("""
                SELECT org_unit_id, reference, title, status_code, criticality_code, criticality_label,
                       fact, criterion, finding_id
                  FROM ccm_exception WHERE id = :id AND tenant_id = :tenant
                """,
                p, (rs, n) -> {
                    Exc e = new Exc();
                    e.orgUnitId = uuid(rs.getBytes("org_unit_id"));
                    e.reference = rs.getString("reference");
                    e.title = rs.getString("title");
                    e.statusCode = rs.getString("status_code");
                    e.criticalityCode = rs.getString("criticality_code");
                    e.criticalityLabel = rs.getString("criticality_label");
                    e.fact = rs.getString("fact");
                    e.criterion = rs.getString("criterion");
                    e.findingId = uuid(rs.getBytes("finding_id"));
                    return e;
                });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static byte[] raw(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }

    private static final class Exc {
        UUID orgUnitId;
        String reference;
        String title;
        String statusCode;
        String criticalityCode;
        String criticalityLabel;
        String fact;
        String criterion;
        UUID findingId;
    }
}
