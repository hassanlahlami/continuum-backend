package com.continuum.audit.internal;

import com.continuum.audit.api.MissionPlanningService;
import com.continuum.audit.api.MissionView;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.transaction.annotation.Transactional;

/**
 * Création de mission (MIS-02) et univers auditable, <strong>PostgreSQL et Oracle</strong> (D4) dans une seule
 * classe : seuls diffèrent la liaison des UUID ({@code UUID} vs {@code RAW(16)}), l'extraction du libellé
 * JSON et le littéral booléen. Pagination {@code OFFSET … FETCH}, valable sur les deux bases.
 *
 * <p>Statut/phase initiaux : fournis par configuration (jamais en dur, CLAUDE.md, paramétrage).
 */
public class JdbcMissionPlanningService implements MissionPlanningService {

    private static final String F_MISSION_READ = "mission.read";
    private static final String F_MISSION_CREATE = "mission.create";
    private static final String F_UNIVERSE_READ = "universe.read";
    private static final String F_UNIVERSE_MANAGE = "universe.manage";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Statut et phase initiaux d'une mission créée (code, libellé). */
    public record InitialState(String statusCode, String statusLabel, String phaseCode, String phaseLabel) {
    }

    private final JdbcTemplate jdbc;
    private final boolean oracle;
    private final AuthorizationService authorization;
    private final AuditTrailService auditTrail;
    private final InitialState initial;

    public JdbcMissionPlanningService(DataSource dataSource, boolean oracle, AuthorizationService authorization,
            AuditTrailService auditTrail, InitialState initial) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.oracle = oracle;
        this.authorization = authorization;
        this.auditTrail = auditTrail;
        this.initial = initial;
    }

    // ---------------------------------------------------------------- dialecte

    private Object id(UUID u) {
        if (u == null) {
            return new SqlParameterValue(oracle ? Types.VARBINARY : Types.OTHER, null);
        }
        if (!oracle) {
            return u;
        }
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }

    private UUID uuid(ResultSet rs, String col) throws SQLException {
        if (oracle) {
            byte[] b = rs.getBytes(col);
            if (b == null) {
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new UUID(bb.getLong(), bb.getLong());
        }
        return rs.getObject(col, UUID.class);
    }

    private String unitLabelExpr() {
        return oracle ? "JSON_VALUE(u.label, '$.fr')" : "u.label ->> 'fr'";
    }

    private String activeExpr() {
        return oracle ? "active = 1" : "active = TRUE";
    }

    private static Object str(String s) {
        return new SqlParameterValue(Types.VARCHAR, s);
    }

    // ------------------------------------------------------------------ droits

    /** Fonctionnalité détenue dans au moins une portée (référentiels à l'échelle du tenant). */
    private boolean holdsAnywhere(UUID tenant, UUID user, String... features) {
        List<ScopedFeature> perms = authorization.permissions(tenant, user);
        for (ScopedFeature p : perms) {
            for (String f : features) {
                if (f.equals(p.featureCode())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- types

    @Override
    public List<MissionTypeView> activeMissionTypes(UUID tenantId, UUID userId) {
        if (!holdsAnywhere(tenantId, userId, F_MISSION_READ, F_MISSION_CREATE)) {
            throw new ForbiddenException("mission.read ou mission.create requis");
        }
        return jdbc.query("SELECT code, label, process_key, default_duration_days FROM mission_type"
                + " WHERE tenant_id = ? AND " + activeExpr() + " ORDER BY label",
                (rs, i) -> new MissionTypeView(rs.getString("code"), rs.getString("label"),
                        rs.getString("process_key"), rs.getObject("default_duration_days", Integer.class)),
                id(tenantId));
    }

    // ----------------------------------------------------------------- univers

    private static final String ENTITY_COLS = "e.id, e.code, e.label, e.kind, e.org_unit_id, u.code AS unit_code,"
            + " %s AS unit_label, e.owner_user_id, e.risk_family_codes, e.regulatory_requirement, e.created_at,"
            + " e.version";

    private AuditableEntityView mapEntity(ResultSet rs) throws SQLException {
        List<String> families;
        try {
            String raw = rs.getString("risk_family_codes");
            families = raw == null ? List.of() : JSON.readValue(raw, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            families = List.of();
        }
        return new AuditableEntityView(uuid(rs, "id"), rs.getString("code"), rs.getString("label"),
                rs.getString("kind"), uuid(rs, "org_unit_id"), rs.getString("unit_code"),
                rs.getString("unit_label"), uuid(rs, "owner_user_id"), families,
                rs.getString("regulatory_requirement"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getInt("version"));
    }

    private String entitySelect() {
        return "SELECT " + ENTITY_COLS.formatted(unitLabelExpr())
                + " FROM auditable_entity e LEFT JOIN org_unit u ON u.id = e.org_unit_id";
    }

    @Override
    public EntityPage listAuditableEntities(UUID tenantId, UUID userId, String query, String kind, UUID unitId,
            int page, int size) {
        if (!holdsAnywhere(tenantId, userId, F_UNIVERSE_READ)) {
            throw new ForbiddenException("universe.read requis");
        }
        StringBuilder where = new StringBuilder(" WHERE e.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(id(tenantId));
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(e.label) LIKE LOWER(?) OR LOWER(e.code) LIKE LOWER(?))");
            String like = "%" + query.strip() + "%";
            args.add(str(like));
            args.add(str(like));
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" AND e.kind = ?");
            args.add(str(kind));
        }
        if (unitId != null) {
            where.append(" AND e.org_unit_id = ?");
            args.add(id(unitId));
        }
        Integer total = jdbc.queryForObject("SELECT count(*) FROM auditable_entity e" + where, Integer.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add((long) page * size);
        pageArgs.add(size);
        List<AuditableEntityView> items = jdbc.query(entitySelect() + where
                + " ORDER BY e.label, e.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, i) -> mapEntity(rs), pageArgs.toArray());
        return new EntityPage(items, total == null ? 0 : total);
    }

    @Override
    public Optional<AuditableEntityView> findAuditableEntity(UUID tenantId, UUID userId, UUID entityId) {
        if (!holdsAnywhere(tenantId, userId, F_UNIVERSE_READ)) {
            throw new ForbiddenException("universe.read requis");
        }
        return jdbc.query(entitySelect() + " WHERE e.tenant_id = ? AND e.id = ?", (rs, i) -> mapEntity(rs),
                id(tenantId), id(entityId)).stream().findFirst();
    }

    @Override
    @Transactional
    public AuditableEntityView createAuditableEntity(CreateEntityCommand c) {
        boolean allowed = c.unitId() != null
                ? authorization.can(c.tenantId(), c.actorUserId(), F_UNIVERSE_MANAGE, c.unitId())
                : holdsAnywhere(c.tenantId(), c.actorUserId(), F_UNIVERSE_MANAGE);
        if (!allowed) {
            throw new ForbiddenException("universe.manage requis");
        }
        if (c.unitId() != null && !exists("org_unit", c.tenantId(), c.unitId())) {
            throw new InvalidRequestException("Unité inconnue");
        }
        UUID newId = UUID.randomUUID();
        String families;
        try {
            families = JSON.writeValueAsString(c.riskFamilyCodes() == null ? List.of() : c.riskFamilyCodes());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        try {
            jdbc.update("INSERT INTO auditable_entity(id, tenant_id, code, label, kind, org_unit_id, owner_user_id,"
                    + " risk_family_codes, regulatory_requirement) VALUES (?,?,?,?,?,?,?,?,?)",
                    id(newId), id(c.tenantId()), str(c.code()), c.label(), c.kind(), id(c.unitId()),
                    id(c.ownerUserId()), families, str(c.regulatoryRequirement()));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Code d'entité auditable déjà utilisé");
        }
        auditTrail.append(new AuditEntry(c.tenantId(), c.actorUserId(), F_UNIVERSE_MANAGE, "AUDITABLE_ENTITY",
                newId.toString(), null));
        return jdbc.query(entitySelect() + " WHERE e.tenant_id = ? AND e.id = ?", (rs, i) -> mapEntity(rs),
                id(c.tenantId()), id(newId)).get(0);
    }

    private boolean exists(String table, UUID tenant, UUID rowId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND id = ?",
                Integer.class, id(tenant), id(rowId));
        return n != null && n > 0;
    }

    // ----------------------------------------------------------------- mission

    @Override
    @Transactional
    public CreatedMission createMission(CreateMissionCommand c) {
        if (!authorization.can(c.tenantId(), c.actorUserId(), F_MISSION_CREATE, c.unitId())) {
            throw new ForbiddenException("mission.create requis sur l'unité");
        }
        if (!exists("org_unit", c.tenantId(), c.unitId())) {
            throw new InvalidRequestException("Unité inconnue");
        }
        if (c.plannedEnd().isBefore(c.plannedStart())) {
            throw new InvalidRequestException("plannedEnd antérieure à plannedStart");
        }
        List<String> typeLabel = jdbc.query("SELECT label FROM mission_type WHERE tenant_id = ? AND code = ? AND "
                + activeExpr(), (rs, i) -> rs.getString(1), id(c.tenantId()), c.missionTypeCode());
        if (typeLabel.isEmpty()) {
            throw new InvalidRequestException("Type de mission inconnu ou inactif : " + c.missionTypeCode());
        }
        List<EntityRef> entities = new ArrayList<>();
        for (UUID eid : c.auditedEntityIds().stream().distinct().toList()) {
            List<EntityRef> found = jdbc.query("SELECT id, label FROM auditable_entity WHERE tenant_id = ? AND id = ?",
                    (rs, i) -> new EntityRef(uuid(rs, "id"), rs.getString("label")), id(c.tenantId()), id(eid));
            if (found.isEmpty()) {
                throw new InvalidRequestException("Entité auditable inconnue : " + eid);
            }
            entities.add(found.get(0));
        }
        List<TeamMemberCommand> team = c.team() == null ? List.of() : c.team();
        List<UUID> roleIds = new ArrayList<>();
        for (TeamMemberCommand m : team) {
            List<UUID> role = jdbc.query("SELECT id FROM role WHERE tenant_id = ? AND code = ? AND kind = 'MISSION'",
                    (rs, i) -> uuid(rs, "id"), id(c.tenantId()), m.missionRoleCode());
            if (role.isEmpty()) {
                throw new InvalidRequestException("Rôle de mission inconnu : " + m.missionRoleCode());
            }
            roleIds.add(role.get(0));
        }

        UUID missionId = UUID.randomUUID();
        String reference = nextReference(c.tenantId(), c.missionTypeCode(), c.plannedStart().getYear());
        Integer budget = c.budgetDays() == null ? null
                : c.budgetDays().setScale(0, RoundingMode.CEILING).intValueExact();
        try {
            jdbc.update("INSERT INTO mission(id, tenant_id, org_unit_id, reference, title, mission_type_code,"
                    + " mission_type_label, status_code, status_label, phase_code, phase_label, progress_percent,"
                    + " planned_start, planned_end, scope, budget_days, audit_plan_item_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?)",
                    id(missionId), id(c.tenantId()), id(c.unitId()), reference, c.title(), c.missionTypeCode(),
                    typeLabel.get(0), initial.statusCode(), initial.statusLabel(), initial.phaseCode(),
                    initial.phaseLabel(), c.plannedStart(), c.plannedEnd(), str(c.scope()),
                    new SqlParameterValue(Types.INTEGER, budget), id(c.auditPlanItemId()));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Référence de mission déjà attribuée, réessayer");
        }
        for (EntityRef e : entities) {
            jdbc.update("INSERT INTO mission_audited_entity(mission_id, auditable_entity_id) VALUES (?,?)",
                    id(missionId), id(e.id()));
        }
        for (int i = 0; i < team.size(); i++) {
            TeamMemberCommand m = team.get(i);
            LocalDate from = m.validFrom() == null ? c.plannedStart() : m.validFrom();
            try {
                jdbc.update("INSERT INTO mission_member(mission_id, user_id, role_id, valid_from, valid_to)"
                        + " VALUES (?,?,?,?,?)", id(missionId), id(m.userId()), id(roleIds.get(i)), from,
                        new SqlParameterValue(Types.DATE, m.validTo()));
            } catch (DuplicateKeyException e) {
                throw new InvalidRequestException("Membre d'équipe en double : " + m.userId());
            }
        }
        String payload = "{\"type\":\"%s\",\"entities\":%d,\"team\":%d}".formatted(c.missionTypeCode(),
                entities.size(), team.size());
        auditTrail.append(new AuditEntry(c.tenantId(), c.actorUserId(), F_MISSION_CREATE, "MISSION",
                missionId.toString(), payload));

        MissionView view = jdbc.query("SELECT m.id, m.reference, m.title, m.mission_type_code, m.mission_type_label,"
                + " m.status_code, m.status_label, m.phase_code, m.phase_label, m.progress_percent, m.planned_start,"
                + " m.planned_end, m.org_unit_id, u.code AS unit_code, " + unitLabelExpr() + " AS unit_label,"
                + " m.created_at, m.version FROM mission m LEFT JOIN org_unit u ON u.id = m.org_unit_id"
                + " WHERE m.id = ?", (rs, i) -> new MissionView(uuid(rs, "id"), rs.getString("reference"),
                        rs.getString("title"), rs.getString("mission_type_code"),
                        rs.getString("mission_type_label"), rs.getString("status_code"),
                        rs.getString("status_label"), rs.getString("phase_code"), rs.getString("phase_label"),
                        rs.getObject("progress_percent", Integer.class),
                        rs.getObject("planned_start", LocalDate.class), rs.getObject("planned_end", LocalDate.class),
                        uuid(rs, "org_unit_id"), rs.getString("unit_code"), rs.getString("unit_label"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getInt("version")),
                id(missionId)).get(0);
        return new CreatedMission(view, c.scope(), entities);
    }

    /** MIS-annee-TYPE-NNN : séquence par tenant/année/type ; l'unicité en base tranche les courses. */
    private String nextReference(UUID tenant, String typeCode, int year) {
        String prefix = "MIS-" + year + "-" + typeCode + "-";
        Integer n = jdbc.queryForObject("SELECT count(*) FROM mission WHERE tenant_id = ? AND reference LIKE ?",
                Integer.class, id(tenant), prefix + "%");
        int seq = (n == null ? 0 : n) + 1;
        return prefix + String.format("%03d", seq);
    }
}
