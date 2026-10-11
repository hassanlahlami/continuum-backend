package com.continuum.audit.internal;

import com.continuum.audit.api.FindingActionService;
import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.FindingView;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.SodViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validation d'un constat (plan §2.1.4, §2.2), PostgreSQL. Le serveur décide : visibilité et droit
 * ({@link AuthorizationService}), SoD dynamique ({@link SegregationOfDutiesService}), puis transition
 * de statut et journalisation signée ({@link AuditTrailService}, §2.3). Tout se joue dans une seule
 * transaction : un conflit annule l'écriture. Le bean est sélectionné par dialecte dans
 * {@link FindingActionConfig} (variante Oracle : {@code RAW(16)}).
 */
public class JdbcFindingActionService implements FindingActionService {

    private static final String FEATURE_VALIDATE = "finding.validate";
    private static final String OBJECT_FINDING = "FINDING";
    private static final String STATUS_OPEN = "OUVERT";

    private final JdbcTemplate jdbc;
    private final FindingDirectory findings;
    private final AuthorizationService authorization;
    private final SegregationOfDutiesService segregationOfDuties;
    private final AuditTrailService auditTrail;

    public JdbcFindingActionService(DataSource dataSource, FindingDirectory findings,
            AuthorizationService authorization, SegregationOfDutiesService segregationOfDuties,
            AuditTrailService auditTrail) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.findings = findings;
        this.authorization = authorization;
        this.segregationOfDuties = segregationOfDuties;
        this.auditTrail = auditTrail;
    }

    @Override
    public List<String> availableActions(UUID tenantId, UUID userId, UUID missionId, FindingView finding) {
        List<String> actions = new ArrayList<>();
        // Valider : constat ouvert + droit finding.validate sur la mission + pas de SoD dynamique bloquante
        // (l'auteur ne peut pas valider son propre constat, §2.2).
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
        // 1. Visibilité : seul un constat de la mission que l'utilisateur peut lire (finding.read) existe.
        FindingView finding = findings
                .findVisible(command.tenantId(), command.actorUserId(), command.missionId(), command.findingId())
                .orElseThrow(FindingNotVisibleException::new);

        // 2. Droit de validation (§2.1.6) : portée d'unité (position VALIDATEUR) OU équipe de mission.
        if (!canValidate(command.tenantId(), command.actorUserId(), command.missionId(), command.findingId())) {
            throw new ActionNotPermittedException();
        }

        // 3. SoD dynamique (§2.2) : l'auteur du constat ne peut pas le valider (même objet).
        List<SodViolation> blocking = blockingDynamicSod(command.tenantId(), command.actorUserId(),
                command.findingId());
        if (!blocking.isEmpty()) {
            throw new DynamicSodViolationException(blocking);
        }

        // 4. Verrou optimiste + transition de statut, idempotent sur la version attendue.
        int updated = jdbc.update("""
                UPDATE finding
                   SET status_code = 'VALIDATED', status_label = 'Validé', version = version + 1
                 WHERE id = ? AND tenant_id = ? AND (? IS NULL OR version = ?)
                """,
                command.findingId(), command.tenantId(),
                command.expectedVersion(), command.expectedVersion());
        if (updated == 0) {
            throw new StaleFindingException(
                    command.expectedVersion() == null ? -1 : command.expectedVersion(), finding.version());
        }

        // 5. Journalisation signée (§2.3) — trace l'action finding.validate sur ce constat.
        String payload = "{\"before\":\"%s\",\"after\":\"VALIDATED\",\"comment\":%s}".formatted(
                finding.statusCode(),
                command.comment() == null ? "null" : "\"" + command.comment().replace("\"", "'") + "\"");
        auditTrail.append(new AuditEntry(command.tenantId(), command.actorUserId(),
                FEATURE_VALIDATE, OBJECT_FINDING, command.findingId().toString(), payload));

        return findings
                .findVisible(command.tenantId(), command.actorUserId(), command.missionId(), command.findingId())
                .orElseThrow(FindingNotVisibleException::new);
    }

    /**
     * Droit de valider le constat (§2.1.6) : soit la fonctionnalité {@code finding.validate} sur l'unité
     * auditée (portée d'unité, ex. position VALIDATEUR), soit via l'équipe de la mission ({@code mission_member}).
     */
    private boolean canValidate(UUID tenantId, UUID userId, UUID missionId, UUID findingId) {
        if (authorization.canOnMission(tenantId, userId, FEATURE_VALIDATE, missionId)) {
            return true;
        }
        UUID unitId = jdbc.query(
                "SELECT org_unit_id FROM finding WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getObject("org_unit_id", UUID.class) : null,
                findingId, tenantId);
        return unitId != null && authorization.can(tenantId, userId, FEATURE_VALIDATE, unitId);
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
}
