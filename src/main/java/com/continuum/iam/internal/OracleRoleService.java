package com.continuum.iam.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.CreateRoleCommand;
import com.continuum.iam.api.RoleService;
import java.nio.ByteBuffer;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écriture des rôles (plan §2.1.1), <strong>dialecte Oracle</strong> (D4).
 *
 * <p>Même logique que {@link JdbcRoleService} (insertion {@code role} + {@code role_feature},
 * journalisation signée §2.3 via {@link AuditTrailService}), mais liaison JDBC adaptée à Oracle :
 * UUID ↔ {@code RAW(16)} (16 octets big-endian) ; le libellé i18n est une chaîne JSON liée
 * directement à la colonne {@code label VARCHAR2(2000) CHECK (label IS JSON)} — pas de cast
 * {@code ?::jsonb} comme en PostgreSQL.
 */
public class OracleRoleService implements RoleService {

    private final JdbcTemplate jdbc;
    private final AuditTrailService auditTrail;

    public OracleRoleService(DataSource dataSource, AuditTrailService auditTrail) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public UUID create(CreateRoleCommand command) {
        UUID roleId = UUID.randomUUID();
        jdbc.update("INSERT INTO role (id, tenant_id, code, label, kind) VALUES (?, ?, ?, ?, ?)",
                raw(roleId), raw(command.tenantId()), command.code(), labelJson(command.label()),
                command.kind());
        for (String featureCode : command.featureCodes()) {
            jdbc.update("INSERT INTO role_feature (role_id, feature_code) VALUES (?, ?)",
                    raw(roleId), featureCode);
        }
        auditTrail.append(new AuditEntry(command.tenantId(), command.actorUserId(),
                "admin.role.manage", "ROLE", roleId.toString(),
                "{\"code\":\"" + command.code() + "\",\"features\":" + command.featureCodes().size() + "}"));
        return roleId;
    }

    private static String labelJson(String label) {
        return "{\"fr\":\"" + label.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    /** UUID → RAW(16) (16 octets big-endian) pour la liaison JDBC Oracle. */
    static byte[] raw(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
