package com.continuum.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.audit.api.MissionPlanningService;
import com.continuum.audit.api.MissionPlanningService.AuditableEntityView;
import com.continuum.audit.api.MissionPlanningService.CreateEntityCommand;
import com.continuum.audit.api.MissionPlanningService.CreateMissionCommand;
import com.continuum.audit.api.MissionPlanningService.CreatedMission;
import com.continuum.audit.api.MissionPlanningService.ForbiddenException;
import com.continuum.audit.api.MissionPlanningService.InvalidRequestException;
import com.continuum.audit.api.MissionPlanningService.TeamMemberCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Scénario commun PostgreSQL / Oracle de MIS-02 (mêmes assertions, parité D4). */
final class MissionPlanningScenario {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID PLANNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    static final UUID IG = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    static final UUID OTHER = UUID.fromString("b0000000-0000-0000-0000-000000000009");

    private MissionPlanningScenario() {
    }

    static void run(MissionPlanningService s, JdbcTemplate jdbc) {
        // Types actifs : seul INSPECTION (DORMANT est désactivé) ; l'étranger est refusé.
        assertThat(s.activeMissionTypes(TENANT, PLANNER)).extracting(MissionPlanningService.MissionTypeView::code)
                .containsExactly("INSPECTION");
        assertThat(s.activeMissionTypes(TENANT, PLANNER).get(0).defaultDurationDays()).isEqualTo(15);
        assertThatThrownBy(() -> s.activeMissionTypes(TENANT, STRANGER)).isInstanceOf(ForbiddenException.class);

        // Univers : création (droit universe.manage), liste filtrée, détail.
        AuditableEntityView agence = s.createAuditableEntity(new CreateEntityCommand(TENANT, PLANNER, "AG-A001",
                "Agence A001", "ORG_UNIT", IG, null, List.of("OPERATIONAL", "FRAUD"), "Circulaire X"));
        assertThat(agence.unitCode()).isEqualTo("IG");
        assertThat(agence.riskFamilyCodes()).containsExactly("OPERATIONAL", "FRAUD");
        s.createAuditableEntity(new CreateEntityCommand(TENANT, PLANNER, "SI-CBS", "Core banking", "SYSTEM", null,
                null, null, null));
        assertThat(s.listAuditableEntities(TENANT, PLANNER, null, null, null, 0, 25).totalItems()).isEqualTo(2);
        assertThat(s.listAuditableEntities(TENANT, PLANNER, "agence", null, null, 0, 25).items()).hasSize(1);
        assertThat(s.listAuditableEntities(TENANT, PLANNER, null, "SYSTEM", null, 0, 25).items()).hasSize(1);
        assertThat(s.listAuditableEntities(TENANT, PLANNER, null, null, IG, 0, 1).items()).hasSize(1);
        assertThat(s.findAuditableEntity(TENANT, PLANNER, agence.id())).isPresent();
        assertThatThrownBy(() -> s.listAuditableEntities(TENANT, STRANGER, null, null, null, 0, 25))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> s.createAuditableEntity(new CreateEntityCommand(TENANT, STRANGER, "X", "X",
                "PROCESS", null, null, null, null))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> s.createAuditableEntity(new CreateEntityCommand(TENANT, PLANNER, "AG-A001", "Dup",
                "PROCESS", null, null, null, null)))
                .isInstanceOf(MissionPlanningService.ConflictException.class);

        // Création de mission.
        UUID member = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        CreateMissionCommand ok = new CreateMissionCommand(TENANT, PLANNER, "Inspection agence A001", "INSPECTION",
                IG, null, List.of(agence.id()), "Caisse et coffre", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 15), new BigDecimal("12"),
                List.of(new TeamMemberCommand(member, "AUDITEUR", null, null)));
        CreatedMission created = s.createMission(ok);
        assertThat(created.mission().reference()).isEqualTo("MIS-2026-INSPECTION-001");
        assertThat(created.mission().statusCode()).isEqualTo("PLANIFIEE");
        assertThat(created.mission().unitCode()).isEqualTo("IG");
        assertThat(created.auditedEntities()).extracting(MissionPlanningService.EntityRef::label)
                .containsExactly("Agence A001");
        assertThat(count(jdbc, "mission_audited_entity")).isEqualTo(1);
        assertThat(count(jdbc, "mission_member")).isEqualTo(1);
        assertThat(count(jdbc, "audit_event WHERE action = 'mission.create'")).isEqualTo(1);
        assertThat(s.createMission(ok).mission().reference()).isEqualTo("MIS-2026-INSPECTION-002");

        // Refus : pas de droit sur l'unité, étranger, références invalides, dates, rôle inconnu.
        assertThatThrownBy(() -> s.createMission(with(ok, PLANNER, OTHER, "INSPECTION", ok.plannedEnd())))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> s.createMission(with(ok, STRANGER, IG, "INSPECTION", ok.plannedEnd())))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> s.createMission(with(ok, PLANNER, IG, "DORMANT", ok.plannedEnd())))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> s.createMission(with(ok, PLANNER, IG, "NOPE", ok.plannedEnd())))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> s.createMission(with(ok, PLANNER, IG, "INSPECTION", LocalDate.of(2026, 11, 1))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> s.createMission(new CreateMissionCommand(TENANT, PLANNER, "T", "INSPECTION", IG,
                null, List.of(UUID.randomUUID()), null, ok.plannedStart(), ok.plannedEnd(), null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> s.createMission(new CreateMissionCommand(TENANT, PLANNER, "T", "INSPECTION", IG,
                null, List.of(agence.id()), null, ok.plannedStart(), ok.plannedEnd(), null,
                List.of(new TeamMemberCommand(member, "ROLE_X", null, null)))))
                .isInstanceOf(InvalidRequestException.class);
        // Rien n'a été écrit par les refus.
        assertThat(count(jdbc, "mission")).isEqualTo(2);
    }

    private static CreateMissionCommand with(CreateMissionCommand b, UUID actor, UUID unit, String type,
            LocalDate end) {
        return new CreateMissionCommand(TENANT, actor, b.title(), type, unit, null, b.auditedEntityIds(), b.scope(),
                b.plannedStart(), end, b.budgetDays(), null);
    }

    private static int count(JdbcTemplate jdbc, String tableAndWhere) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + tableAndWhere, Integer.class);
        return n == null ? 0 : n;
    }
}
