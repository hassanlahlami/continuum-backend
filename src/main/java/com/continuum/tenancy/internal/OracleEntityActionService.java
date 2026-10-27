package com.continuum.tenancy.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.tenancy.api.EntityActionService;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implémentation <strong>Oracle</strong> (D4) de {@link EntityActionService}, à parité avec
 * {@link JdbcEntityActionService} : UUID liés en {@code RAW(16)}, {@code active} en {@code NUMBER(1)}.
 */
public class OracleEntityActionService implements EntityActionService {

    private static final String FEATURE = "admin.entity.manage";
    private static final String OBJECT = "ENTITY";

    private final JdbcTemplate jdbc;
    private final AuditTrailService auditTrail;

    public OracleEntityActionService(DataSource dataSource, AuditTrailService auditTrail) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public EntityView perform(EntityActionCommand c) {
        EntityView before = load(c.entityId());
        String target = EntityTransitions.target(before.status(), c.actionCode());
        int active = EntityTransitions.isActive(target) ? 1 : 0;
        int updated = c.expectedVersion() == null
                ? jdbc.update("UPDATE platform.tenant SET status = ?, active = ?, version = version + 1 WHERE id = ?",
                        target, active, raw(c.entityId()))
                : jdbc.update("UPDATE platform.tenant SET status = ?, active = ?, version = version + 1"
                        + " WHERE id = ? AND version = ?",
                        target, active, raw(c.entityId()), c.expectedVersion());
        if (updated == 0) {
            throw new StaleEntityException(c.expectedVersion() == null ? -1 : c.expectedVersion(), before.version());
        }
        auditTrail.append(new AuditEntry(c.actorTenantId(), c.actorUserId(), FEATURE, OBJECT,
                c.entityId().toString(), EntityTransitions.auditPayload(c.actionCode(), before.status(), target)));
        return load(c.entityId());
    }

    private EntityView load(UUID id) {
        List<EntityView> rows = jdbc.query(
                "SELECT id, code, label, status, version FROM platform.tenant WHERE id = ?",
                (rs, i) -> new EntityView(uuid(rs.getBytes("id")), rs.getString("code"),
                        rs.getString("label"), rs.getString("status"), rs.getInt("version")),
                raw(id));
        return rows.stream().findFirst().orElseThrow(() -> new UnknownEntityException(id));
    }

    private static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
