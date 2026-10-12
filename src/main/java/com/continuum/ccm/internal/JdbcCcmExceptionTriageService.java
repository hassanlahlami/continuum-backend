package com.continuum.ccm.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.ccm.api.CcmExceptionTriageService;
import com.continuum.iam.api.AuthorizationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triage d'une exception CCM (plan §5.3, D34), PostgreSQL. Le serveur décide : droit
 * {@code ccm.exception.triage} ({@link AuthorizationService}), transition de statut valide, verrou
 * optimiste, puis journalisation signée ({@link AuditTrailService}, §2.3). Tout en une transaction. Le
 * bean est sélectionné par dialecte dans {@link CcmExceptionTriageConfig} (variante Oracle : {@code RAW(16)}).
 */
public class JdbcCcmExceptionTriageService implements CcmExceptionTriageService {

    private static final String FEATURE_TRIAGE = "ccm.exception.triage";
    private static final String OBJECT = "CCM_EXCEPTION";

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditTrailService auditTrail;

    public JdbcCcmExceptionTriageService(
            DataSource dataSource, AuthorizationService authorization, AuditTrailService auditTrail) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.authorization = authorization;
        this.auditTrail = auditTrail;
    }

    @Override
    public List<String> availableActions(UUID tenantId, UUID userId, CcmExceptionView exception) {
        List<String> actions = new ArrayList<>();
        if (!canTriage(tenantId, userId, exception.orgUnitId())) {
            return actions;
        }
        switch (exception.statusCode()) {
            case STATUS_NEW -> {
                actions.add(ACTION_RETAIN);
                actions.add(ACTION_DISMISS);
                actions.add(ACTION_ASSIGN);
            }
            case STATUS_RETAINED -> actions.add(ACTION_ASSIGN);
            case STATUS_DISMISSED -> actions.add(ACTION_REOPEN);
            default -> { /* statut terminal : aucune action de triage */ }
        }
        return actions;
    }

    @Override
    @Transactional
    public CcmExceptionView triage(TriageCommand command) {
        CcmExceptionView exception = load(command.tenantId(), command.exceptionId())
                .orElseThrow(CcmExceptionNotVisibleException::new);

        if (!canTriage(command.tenantId(), command.actorUserId(), exception.orgUnitId())) {
            throw new TriageNotPermittedException();
        }

        String action = command.actionCode();
        String before = exception.statusCode();
        switch (action) {
            case ACTION_RETAIN -> {
                requireStatus(exception, STATUS_NEW, action);
                updateStatus(command, STATUS_RETAINED, "Retenue");
            }
            case ACTION_DISMISS -> {
                requireStatus(exception, STATUS_NEW, action);
                if (isBlank(command.justification())) {
                    throw new JustificationRequiredException();
                }
                dismiss(command);
            }
            case ACTION_ASSIGN -> {
                if (STATUS_DISMISSED.equals(before)) {
                    throw new IllegalTriageTransitionException(before, action);
                }
                if (command.assigneeUserId() == null) {
                    throw new IllegalArgumentException("Affecter une exception exige un destinataire.");
                }
                assign(command);
            }
            case ACTION_REOPEN -> {
                requireStatus(exception, STATUS_DISMISSED, action);
                reopen(command);
            }
            default -> throw new IllegalArgumentException("Action de triage inconnue : " + action);
        }

        auditTrail.append(new AuditEntry(command.tenantId(), command.actorUserId(),
                FEATURE_TRIAGE, OBJECT, command.exceptionId().toString(), payload(action, before, command)));

        return load(command.tenantId(), command.exceptionId()).orElseThrow(CcmExceptionNotVisibleException::new);
    }

    private void updateStatus(TriageCommand c, String statusCode, String statusLabel) {
        apply(c, """
                UPDATE ccm_exception
                   SET status_code = ?, status_label = ?, triaged_by = ?, triaged_at = now(), version = version + 1
                 WHERE id = ? AND tenant_id = ? AND (? IS NULL OR version = ?)
                """, statusCode, statusLabel, c.actorUserId(),
                c.exceptionId(), c.tenantId(), c.expectedVersion(), c.expectedVersion());
    }

    private void dismiss(TriageCommand c) {
        apply(c, """
                UPDATE ccm_exception
                   SET status_code = 'DISMISSED', status_label = 'Écartée', dismissal_justification = ?,
                       triaged_by = ?, triaged_at = now(), version = version + 1
                 WHERE id = ? AND tenant_id = ? AND (? IS NULL OR version = ?)
                """, c.justification(), c.actorUserId(),
                c.exceptionId(), c.tenantId(), c.expectedVersion(), c.expectedVersion());
    }

    private void assign(TriageCommand c) {
        apply(c, """
                UPDATE ccm_exception
                   SET assignee_user_id = ?, triaged_by = ?, triaged_at = now(), version = version + 1
                 WHERE id = ? AND tenant_id = ? AND (? IS NULL OR version = ?)
                """, c.assigneeUserId(), c.actorUserId(),
                c.exceptionId(), c.tenantId(), c.expectedVersion(), c.expectedVersion());
    }

    private void reopen(TriageCommand c) {
        apply(c, """
                UPDATE ccm_exception
                   SET status_code = 'NEW', status_label = 'À trier', dismissal_justification = NULL,
                       triaged_by = ?, triaged_at = now(), version = version + 1
                 WHERE id = ? AND tenant_id = ? AND (? IS NULL OR version = ?)
                """, c.actorUserId(), c.exceptionId(), c.tenantId(), c.expectedVersion(), c.expectedVersion());
    }

    private void apply(TriageCommand c, String sql, Object... args) {
        int updated = jdbc.update(sql, args);
        if (updated == 0) {
            int actual = load(c.tenantId(), c.exceptionId()).map(CcmExceptionView::version).orElse(-1);
            throw new StaleCcmExceptionException(c.expectedVersion() == null ? -1 : c.expectedVersion(), actual);
        }
    }

    /**
     * Droit de triage (§2.1.6) : {@code ccm.exception.triage} sur l'unité de l'exception, ou — pour une
     * exception sans unité (contrôle population au périmètre du tenant) — à la racine du tenant.
     */
    private boolean canTriage(UUID tenantId, UUID userId, UUID orgUnitId) {
        UUID scopeUnit = orgUnitId != null ? orgUnitId : rootUnit(tenantId);
        return scopeUnit != null && authorization.can(tenantId, userId, FEATURE_TRIAGE, scopeUnit);
    }

    private UUID rootUnit(UUID tenantId) {
        return jdbc.query(
                "SELECT id FROM org_unit WHERE tenant_id = ? AND parent_id IS NULL ORDER BY id FETCH FIRST 1 ROW ONLY",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenantId);
    }

    private java.util.Optional<CcmExceptionView> load(UUID tenantId, UUID exceptionId) {
        List<CcmExceptionView> rows = jdbc.query("""
                SELECT id, tenant_id, org_unit_id, reference, control_code, title, status_code, status_label,
                       criticality_code, assignee_user_id, dismissal_justification, finding_id, version
                  FROM ccm_exception WHERE id = ? AND tenant_id = ?
                """,
                (rs, n) -> new CcmExceptionView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class),
                        rs.getString("reference"),
                        rs.getString("control_code"),
                        rs.getString("title"),
                        rs.getString("status_code"),
                        rs.getString("status_label"),
                        rs.getString("criticality_code"),
                        rs.getObject("assignee_user_id", UUID.class),
                        rs.getString("dismissal_justification"),
                        rs.getObject("finding_id", UUID.class),
                        rs.getInt("version")),
                exceptionId, tenantId);
        return rows.stream().findFirst();
    }

    private void requireStatus(CcmExceptionView exception, String expected, String action) {
        if (!expected.equals(exception.statusCode())) {
            throw new IllegalTriageTransitionException(exception.statusCode(), action);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String payload(String action, String before, TriageCommand c) {
        StringBuilder sb = new StringBuilder("{\"action\":\"").append(action).append("\",\"before\":\"")
                .append(before).append("\"");
        if (c.justification() != null) {
            sb.append(",\"justification\":\"").append(c.justification().replace("\"", "'")).append("\"");
        }
        if (c.assigneeUserId() != null) {
            sb.append(",\"assignee\":\"").append(c.assigneeUserId()).append("\"");
        }
        return sb.append("}").toString();
    }
}
