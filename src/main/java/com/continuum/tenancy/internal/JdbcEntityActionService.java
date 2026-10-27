package com.continuum.tenancy.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.tenancy.api.EntityActionService;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Implémentation PostgreSQL de {@link EntityActionService} sur {@code platform.tenant}. */
public class JdbcEntityActionService implements EntityActionService {

    private static final String FEATURE = "admin.entity.manage";
    private static final String OBJECT = "ENTITY";

    private final JdbcTemplate jdbc;
    private final AuditTrailService auditTrail;

    public JdbcEntityActionService(DataSource dataSource, AuditTrailService auditTrail) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public EntityView perform(EntityActionCommand c) {
        EntityView before = load(c.entityId());
        String target = EntityTransitions.target(before.status(), c.actionCode());
        boolean active = EntityTransitions.isActive(target);
        int updated = c.expectedVersion() == null
                ? jdbc.update("UPDATE platform.tenant SET status = ?, active = ?, version = version + 1 WHERE id = ?",
                        target, active, c.entityId())
                : jdbc.update("UPDATE platform.tenant SET status = ?, active = ?, version = version + 1"
                        + " WHERE id = ? AND version = ?",
                        target, active, c.entityId(), c.expectedVersion());
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
                (rs, i) -> new EntityView(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("label"), rs.getString("status"), rs.getInt("version")),
                id);
        return rows.stream().findFirst().orElseThrow(() -> new UnknownEntityException(id));
    }
}
