package com.continuum.ccm.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.ccm.api.CcmExceptionConversionService;
import com.continuum.iam.api.AuthorizationService;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversion d'une exception CCM retenue en constat candidat (plan §5.3, D34), PostgreSQL. Décision
 * serveur : droit {@code ccm.exception.convert}, exception {@code RETAINED} non déjà convertie, mission
 * existante ; insertion du constat (source {@code CCM}, FRAP pré-remplie), liaison {@code finding_id} sur
 * l'exception, journalisation signée (§2.3). Le tout dans une transaction. Sélection par dialecte dans
 * {@link CcmExceptionConversionConfig} (variante Oracle : {@code RAW(16)}).
 */
public class JdbcCcmExceptionConversionService implements CcmExceptionConversionService {

    private static final String OBJECT = "CCM_EXCEPTION";

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditTrailService auditTrail;

    public JdbcCcmExceptionConversionService(
            DataSource dataSource, AuthorizationService authorization, AuditTrailService auditTrail) {
        this.jdbc = new JdbcTemplate(dataSource);
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

        UUID findingId = jdbc.queryForObject("""
                INSERT INTO finding (id, tenant_id, mission_id, org_unit_id, reference, title,
                                     status_code, status_label, criticality_code, criticality_label, source,
                                     fact, criterion)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'OUVERT', 'Ouvert', ?, ?, 'CCM', ?, ?)
                RETURNING id
                """, UUID.class,
                command.tenantId(), command.missionId(), missionUnit, exc.reference, exc.title,
                exc.criticalityCode, exc.criticalityLabel, exc.fact, exc.criterion);

        jdbc.update("UPDATE ccm_exception SET finding_id = ?, version = version + 1 WHERE id = ? AND tenant_id = ?",
                findingId, command.exceptionId(), command.tenantId());

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
        return jdbc.query(
                "SELECT id FROM org_unit WHERE tenant_id = ? AND parent_id IS NULL ORDER BY id FETCH FIRST 1 ROW ONLY",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenantId);
    }

    private UUID missionUnit(UUID tenantId, UUID missionId) {
        return jdbc.query("SELECT org_unit_id FROM mission WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getObject("org_unit_id", UUID.class) : null, missionId, tenantId);
    }

    private Exc loadException(UUID tenantId, UUID exceptionId) {
        List<Exc> rows = jdbc.query("""
                SELECT org_unit_id, reference, title, status_code, criticality_code, criticality_label,
                       fact, criterion, finding_id
                  FROM ccm_exception WHERE id = ? AND tenant_id = ?
                """,
                (rs, n) -> {
                    Exc e = new Exc();
                    e.orgUnitId = rs.getObject("org_unit_id", UUID.class);
                    e.reference = rs.getString("reference");
                    e.title = rs.getString("title");
                    e.statusCode = rs.getString("status_code");
                    e.criticalityCode = rs.getString("criticality_code");
                    e.criticalityLabel = rs.getString("criticality_label");
                    e.fact = rs.getString("fact");
                    e.criterion = rs.getString("criterion");
                    e.findingId = rs.getObject("finding_id", UUID.class);
                    return e;
                }, exceptionId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Projection interne minimale de l'exception pour la conversion. */
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
