package com.continuum.audit.internal;

import com.continuum.audit.api.FindingActionService;
import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.FindingView;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.SodViolation;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validation d'un constat (plan §2.1.4, §2.2), PostgreSQL. Le serveur décide : visibilité et droit
 * ({@link AuthorizationService}), SoD dynamique ({@link SegregationOfDutiesService}), puis transition
 * de statut et journalisation signée ({@link AuditTrailService}, §2.3). Tout se joue dans une seule
 * transaction : un conflit annule l'écriture.
 */
@Service
public class JdbcFindingActionService implements FindingActionService {

    private static final String FEATURE_VALIDATE = "finding.validate";
    private static final String OBJECT_FINDING = "FINDING";

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
    @Transactional
    public FindingView validate(ValidateFindingCommand command) {
        // 1. Visibilité : seul un constat de la mission que l'utilisateur peut lire (finding.read) existe.
        FindingView finding = findings
                .findVisible(command.tenantId(), command.actorUserId(), command.missionId(), command.findingId())
                .orElseThrow(FindingNotVisibleException::new);

        // 2. Droit de validation sur l'unité auditée de la mission (§2.1.6).
        if (!authorization.canOnMission(command.tenantId(), command.actorUserId(), FEATURE_VALIDATE,
                command.missionId())) {
            throw new ActionNotPermittedException();
        }

        // 3. SoD dynamique (§2.2) : l'auteur du constat ne peut pas le valider (même objet).
        List<SodViolation> blocking = segregationOfDuties
                .dynamicConflicts(command.tenantId(), command.actorUserId(), FEATURE_VALIDATE,
                        OBJECT_FINDING, command.findingId().toString())
                .stream()
                .filter(v -> v.severity() == SodViolation.Severity.BLOCK)
                .toList();
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
}
