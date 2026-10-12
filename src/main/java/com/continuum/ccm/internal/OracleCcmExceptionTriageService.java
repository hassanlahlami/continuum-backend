package com.continuum.ccm.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.ccm.api.CcmExceptionTriageService;
import com.continuum.iam.api.AuthorizationService;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triage d'une exception CCM (plan §5.3, D34) — implémentation <strong>Oracle</strong> (D4), à parité
 * avec {@link JdbcCcmExceptionTriageService}. Même logique de décision serveur (droit, transition, verrou
 * optimiste, journalisation signée) ; spécifique au dialecte : UUID liés en {@code RAW(16)},
 * {@code SYSTIMESTAMP}, et clause de version ajoutée seulement si attendue (évite un NULL non typé).
 */
public class OracleCcmExceptionTriageService implements CcmExceptionTriageService {

    private static final String FEATURE_TRIAGE = "ccm.exception.triage";
    private static final String OBJECT = "CCM_EXCEPTION";

    private final NamedParameterJdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditTrailService auditTrail;

    public OracleCcmExceptionTriageService(
            DataSource dataSource, AuthorizationService authorization, AuditTrailService auditTrail) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
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
            default -> { /* statut terminal */ }
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
                apply(command, "status_code = 'RETAINED', status_label = 'Retenue'", null);
            }
            case ACTION_DISMISS -> {
                requireStatus(exception, STATUS_NEW, action);
                if (isBlank(command.justification())) {
                    throw new JustificationRequiredException();
                }
                apply(command, "status_code = 'DISMISSED', status_label = 'Écartée',"
                        + " dismissal_justification = :justification", p -> p.addValue("justification",
                        command.justification()));
            }
            case ACTION_ASSIGN -> {
                if (STATUS_DISMISSED.equals(before)) {
                    throw new IllegalTriageTransitionException(before, action);
                }
                if (command.assigneeUserId() == null) {
                    throw new IllegalArgumentException("Affecter une exception exige un destinataire.");
                }
                apply(command, "assignee_user_id = :assignee",
                        p -> p.addValue("assignee", raw(command.assigneeUserId())));
            }
            case ACTION_REOPEN -> {
                requireStatus(exception, STATUS_DISMISSED, action);
                apply(command, "status_code = 'NEW', status_label = 'À trier', dismissal_justification = NULL", null);
            }
            default -> throw new IllegalArgumentException("Action de triage inconnue : " + action);
        }

        auditTrail.append(new AuditEntry(command.tenantId(), command.actorUserId(),
                FEATURE_TRIAGE, OBJECT, command.exceptionId().toString(), payload(action, before, command)));

        return load(command.tenantId(), command.exceptionId()).orElseThrow(CcmExceptionNotVisibleException::new);
    }

    /** Applique le SET donné + horodatage/auteur/version, sous verrou optimiste. */
    private void apply(TriageCommand c, String setClause, java.util.function.Consumer<MapSqlParameterSource> extra) {
        String sql = "UPDATE ccm_exception SET " + setClause
                + ", triaged_by = :actor, triaged_at = SYSTIMESTAMP, version = version + 1"
                + " WHERE id = :id AND tenant_id = :tenant";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", raw(c.exceptionId()))
                .addValue("tenant", raw(c.tenantId()))
                .addValue("actor", raw(c.actorUserId()));
        if (extra != null) {
            extra.accept(params);
        }
        if (c.expectedVersion() != null) {
            sql += " AND version = :expected";
            params.addValue("expected", c.expectedVersion());
        }
        int updated = jdbc.update(sql, params);
        if (updated == 0) {
            int actual = load(c.tenantId(), c.exceptionId()).map(CcmExceptionView::version).orElse(-1);
            throw new StaleCcmExceptionException(c.expectedVersion() == null ? -1 : c.expectedVersion(), actual);
        }
    }

    private boolean canTriage(UUID tenantId, UUID userId, UUID orgUnitId) {
        UUID scopeUnit = orgUnitId != null ? orgUnitId : rootUnit(tenantId);
        return scopeUnit != null && authorization.can(tenantId, userId, FEATURE_TRIAGE, scopeUnit);
    }

    private UUID rootUnit(UUID tenantId) {
        List<byte[]> rows = jdbc.query(
                "SELECT id FROM org_unit WHERE tenant_id = :tenant AND parent_id IS NULL"
                + " ORDER BY id FETCH FIRST 1 ROW ONLY",
                new MapSqlParameterSource("tenant", raw(tenantId)), (rs, i) -> rs.getBytes("id"));
        return rows.isEmpty() ? null : uuid(rows.get(0));
    }

    private Optional<CcmExceptionView> load(UUID tenantId, UUID exceptionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", raw(exceptionId))
                .addValue("tenant", raw(tenantId));
        List<CcmExceptionView> rows = jdbc.query("""
                SELECT id, tenant_id, org_unit_id, reference, control_code, title, status_code, status_label,
                       criticality_code, assignee_user_id, dismissal_justification, finding_id, version
                  FROM ccm_exception WHERE id = :id AND tenant_id = :tenant
                """,
                params, (rs, n) -> new CcmExceptionView(
                        uuid(rs.getBytes("id")),
                        uuid(rs.getBytes("tenant_id")),
                        uuid(rs.getBytes("org_unit_id")),
                        rs.getString("reference"),
                        rs.getString("control_code"),
                        rs.getString("title"),
                        rs.getString("status_code"),
                        rs.getString("status_label"),
                        rs.getString("criticality_code"),
                        uuid(rs.getBytes("assignee_user_id")),
                        rs.getString("dismissal_justification"),
                        uuid(rs.getBytes("finding_id")),
                        rs.getInt("version")));
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
}
