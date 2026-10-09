package com.continuum.iam.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.CreateRoleCommand;
import com.continuum.iam.api.RoleService;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écriture des rôles (plan §2.1.1), PostgreSQL. Le libellé i18n est stocké en JSONB ; chaque création
 * est journalisée dans le journal signé (§2.3) via l'API du module {@code audittrail}.
 *
 * <p>Instanciée par {@link RoleServiceConfig} selon le dialecte (D4) — pas de {@code @Service} pour
 * éviter un bean {@link RoleService} en double avec {@link OracleRoleService}.
 */
public class JdbcRoleService implements RoleService {

    private final JdbcTemplate jdbc;
    private final AuditTrailService auditTrail;

    public JdbcRoleService(DataSource dataSource, AuditTrailService auditTrail) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public UUID create(CreateRoleCommand command) {
        UUID roleId = UUID.randomUUID();
        jdbc.update("INSERT INTO role (id, tenant_id, code, label, kind) VALUES (?, ?, ?, ?::jsonb, ?)",
                roleId, command.tenantId(), command.code(), labelJson(command.label()), command.kind());
        for (String featureCode : command.featureCodes()) {
            jdbc.update("INSERT INTO role_feature (role_id, feature_code) VALUES (?, ?)", roleId, featureCode);
        }
        auditTrail.append(new AuditEntry(command.tenantId(), command.actorUserId(),
                "admin.role.manage", "ROLE", roleId.toString(),
                "{\"code\":\"" + command.code() + "\",\"features\":" + command.featureCodes().size() + "}"));
        return roleId;
    }

    private static String labelJson(String label) {
        return "{\"fr\":\"" + label.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
