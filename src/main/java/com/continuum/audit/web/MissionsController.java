package com.continuum.audit.web;

import com.continuum.audit.api.MissionDirectory;
import com.continuum.audit.api.MissionPlanningService;
import com.continuum.audit.api.MissionPlanningService.CreateMissionCommand;
import com.continuum.audit.api.MissionPlanningService.CreatedMission;
import com.continuum.audit.api.MissionPlanningService.TeamMemberCommand;
import com.continuum.audit.api.MissionView;
import gen.app.missions.api.MissionsApi;
import gen.app.missions.model.AuditMeta;
import gen.app.missions.model.Mission;
import gen.app.missions.model.MissionAuditedEntitiesInner;
import gen.app.missions.model.MissionCreate;
import gen.app.missions.model.MissionType;
import gen.app.missions.model.MissionPage;
import gen.app.missions.model.Ref;
import gen.app.missions.model.UnitSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Missions de la Plateforme Fonctionnelle (contrat {@code app/missions.yaml}, §6) : {@code GET
 * /missions} (liste paginée du périmètre) et {@code GET /missions/{id}} (détail). Implémente
 * l'interface <strong>générée</strong> (D32) ; le périmètre ({@code mission.read}) est appliqué par le
 * read model {@link MissionDirectory}. Le tenant et l'utilisateur viennent du jeton.
 */
@RestController
@RequestMapping("/api/app")
public class MissionsController implements MissionsApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";

    private final MissionDirectory missions;
    private final MissionPlanningService planning;

    public MissionsController(MissionDirectory missions, MissionPlanningService planning) {
        this.planning = planning;
        this.missions = missions;
    }

    @Override
    public ResponseEntity<MissionPage> listMissions(Integer page, Integer size, List<String> sort, String q,
            List<String> status, UUID unitId, LocalDate from, LocalDate to, String missionTypeCode,
            UUID auditPlanId, Boolean mine) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        int p = page == null ? 0 : page;
        int s = size == null ? 25 : size;

        MissionDirectory.Page result = missions.listVisible(tenant, user, q, p, s);
        int totalPages = (int) Math.ceil(result.totalItems() / (double) s);
        List<Mission> items = result.items().stream().map(MissionsController::toModel).toList();
        return ResponseEntity.ok(new MissionPage(p, s, result.totalItems(), totalPages, items));
    }

    @Override
    public ResponseEntity<Mission> getMission(UUID missionId) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        return missions.findVisible(tenant, user, missionId)
                .map(MissionsController::toModel)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission inconnue"));
    }

    @Override
    public ResponseEntity<List<MissionType>> listActiveMissionTypes() {
        Jwt jwt = currentJwt();
        List<MissionType> types = UniverseController.guard(() -> planning
                .activeMissionTypes(uuidClaim(jwt, CLAIM_TENANT), uuidClaim(jwt, CLAIM_USER))).stream()
                .map(t -> new MissionType(t.code(), t.label()).processKey(t.processKey())
                        .defaultDurationDays(t.defaultDurationDays()))
                .toList();
        return ResponseEntity.ok(types);
    }

    /** Dernière étape de l'assistant MIS-02 : droit {@code mission.create} sur l'unité, références validées (422). */
    @Override
    public ResponseEntity<Mission> createMission(MissionCreate in) {
        Jwt jwt = currentJwt();
        List<TeamMemberCommand> team = in.getTeam() == null ? List.of() : in.getTeam().stream()
                .map(m -> new TeamMemberCommand(m.getUserId(), m.getMissionRoleCode(), m.getValidFrom(),
                        m.getValidTo()))
                .toList();
        CreatedMission created = UniverseController.guard(() -> planning.createMission(new CreateMissionCommand(
                uuidClaim(jwt, CLAIM_TENANT), uuidClaim(jwt, CLAIM_USER), in.getTitle(), in.getMissionTypeCode(),
                in.getUnitId(), in.getAuditPlanItemId(), in.getAuditedEntityIds(), in.getScope(),
                in.getPlannedStart(), in.getPlannedEnd(), in.getBudgetDays(), team)));
        Mission body = toModel(created.mission()).scope(created.scope());
        created.auditedEntities().forEach(e -> body.addAuditedEntitiesItem(
                new MissionAuditedEntitiesInner().id(e.id()).label(e.label())));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static Mission toModel(MissionView v) {
        Mission mission = new Mission(v.id(), v.reference(), v.title(),
                new Ref(v.missionTypeCode(), v.missionTypeLabel()),
                new Ref(v.statusCode(), v.statusLabel()),
                new Ref(v.phaseCode(), v.phaseLabel()),
                new AuditMeta(v.createdAt(), v.version()))
                .progressPercent(v.progressPercent())
                .plannedStart(v.plannedStart())
                .plannedEnd(v.plannedEnd());
        if (v.unitId() != null) {
            mission.unit(new UnitSummary(v.unitId(), v.unitLabel() == null ? v.unitCode() : v.unitLabel())
                    .code(v.unitCode()));
        }
        return mission;
    }

    private static UUID uuidClaim(Jwt jwt, String claim) {
        return UUID.fromString(jwt.getClaimAsString(claim));
    }

    private static Jwt currentJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        return jwt;
    }
}
