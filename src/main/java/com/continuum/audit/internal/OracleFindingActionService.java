package com.continuum.audit.internal;

import com.continuum.audit.api.FindingActionService;
import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.FindingView;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.SodViolation;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validation d'un constat (plan §2.1.4, §2.2) — implémentation <strong>Oracle</strong> (D4), à parité
 * avec PostgreSQL. Même logique de décision serveur (visibilité, droit unité-ou-mission, SoD dynamique,
 * verrou optimiste, journalisation signée) ; spécifique au dialecte : UUID liés en {@code RAW(16)}.
 */
public class OracleFindingActionService implements FindingActionService {

    private static final String FEATURE_VALIDATE = "finding.validate";
    private static final String OBJECT_FINDING = "FINDING";
    private static final String STATUS_OPEN = "OUVERT";

    private final NamedParameterJdbcTemplate jdbc;
    private final FindingDirectory findings;
    private final AuthorizationService authorization;
    private final SegregationOfDutiesService segregationOfDuties;
    private final AuditTrailService auditTrail;

    public OracleFindingActionService(DataSource dataSource, FindingDirectory findings,
            AuthorizationService authorization, SegregationOfDutiesService segregationOfDuties,
            AuditTrailService auditTrail) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.findings = findings;
        this.authorization = authorization;
        this.segregationOfDuties = segregationOfDuties;
        this.auditTrail = auditTrail;
    }

    @Override
    public List<String> availableActions(UUID tenantId, UUID userId, UUID missionId, FindingView finding) {
        List<String> actions = new ArrayList<>();
        if (STATUS_OPEN.equals(finding.statusCode())
                && canValidate(tenantId, userId, missionId, finding.id())
                && !hasBlockingDynamicSod(tenantId, userId, finding.id())) {
            actions.add(ACTION_VALIDATE);
        }
        return actions;
    }

    @Override
    @Transactional
    public FindingView validate(ValidateFindingCommand command) {
        FindingView finding = findings
                .findVisible(command.tenantId(), command.actorUserId(), command.missionId(), command.findingId())
                .orElseThrow(FindingNotVisibleException::new);

        if (!canValidate(command.tenantId(), command.actorUserId(), command.missionId(), command.findingId())) {
            throw new ActionNotPermittedException();
        }

        List<SodViolation> blocking = blockingDynamicSod(command.tenantId(), command.actorUserId(),
                command.findingId());
        if (!blocking.isEmpty()) {
            throw new DynamicSodViolationException(blocking);
        }

        // Verrou optimiste : la clause de version n'est ajoutée que si une version est attendue (évite
        // de lier un NULL non typé sur Oracle).
        String sql = "UPDATE finding SET status_code = 'VALIDATED', status_label = 'Validé',"
                + " version = version + 1 WHERE id = :finding AND tenant_id = :tenant";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("finding", raw(command.findingId()))
                .addValue("tenant", raw(command.tenantId()));
        if (command.expectedVersion() != null) {
            sql += " AND version = :expected";
            params.addValue("expected", command.expectedVersion());
        }
        int updated = jdbc.update(sql, params);
        if (updated == 0) {
            throw new StaleFindingException(
                    command.expectedVersion() == null ? -1 : command.expectedVersion(), finding.version());
        }

        String payload = "{\"before\":\"%s\",\"after\":\"VALIDATED\",\"comment\":%s}".formatted(
                finding.statusCode(),
                command.comment() == null ? "null" : "\"" + command.comment().replace("\"", "'") + "\"");
        auditTrail.append(new AuditEntry(command.tenantId(), command.actorUserId(),
                FEATURE_VALIDATE, OBJECT_FINDING, command.findingId().toString(), payload));

        return findings
                .findVisible(command.tenantId(), command.actorUserId(), command.missionId(), command.findingId())
                .orElseThrow(FindingNotVisibleException::new);
    }

    private boolean canValidate(UUID tenantId, UUID userId, UUID missionId, UUID findingId) {
        if (authorization.canOnMission(tenantId, userId, FEATURE_VALIDATE, missionId)) {
            return true;
        }
        UUID unitId = unitOf(tenantId, findingId);
        return unitId != null && authorization.can(tenantId, userId, FEATURE_VALIDATE, unitId);
    }

    private UUID unitOf(UUID tenantId, UUID findingId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("finding", raw(findingId))
                .addValue("tenant", raw(tenantId));
        List<byte[]> rows = jdbc.query(
                "SELECT org_unit_id FROM finding WHERE id = :finding AND tenant_id = :tenant",
                params, (rs, i) -> rs.getBytes("org_unit_id"));
        return rows.isEmpty() ? null : uuid(rows.get(0));
    }

    private boolean hasBlockingDynamicSod(UUID tenantId, UUID userId, UUID findingId) {
        return !blockingDynamicSod(tenantId, userId, findingId).isEmpty();
    }

    private List<SodViolation> blockingDynamicSod(UUID tenantId, UUID userId, UUID findingId) {
        return segregationOfDuties
                .dynamicConflicts(tenantId, userId, FEATURE_VALIDATE, OBJECT_FINDING, findingId.toString())
                .stream()
                .filter(v -> v.severity() == SodViolation.Severity.BLOCK)
                .toList();
    }

    /** UUID → RAW(16) (16 octets big-endian) pour la liaison JDBC Oracle. */
    private static byte[] raw(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    /** RAW(16) → UUID. */
    private static UUID uuid(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
