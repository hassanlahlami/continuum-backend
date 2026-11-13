package com.continuum.audit.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Création d'une mission (assistant MIS-02, §6) et univers d'audit (contrat {@code app/missions.yaml} et
 * {@code app/planning.yaml}). Le serveur décide : droits via {@code can()} (§2.1.6), validation des
 * références (type de mission du tenant, unité, entités auditables), journalisation signée (§2.3).
 */
public interface MissionPlanningService {

    record MissionTypeView(String code, String label, String processKey, Integer defaultDurationDays) {
    }

    record AuditableEntityView(UUID id, String code, String label, String kind, UUID unitId, String unitCode,
            String unitLabel, UUID ownerUserId, List<String> riskFamilyCodes, String regulatoryRequirement,
            OffsetDateTime createdAt, int version) {
    }

    record EntityPage(List<AuditableEntityView> items, int totalItems) {
    }

    record EntityRef(UUID id, String label) {
    }

    record TeamMemberCommand(UUID userId, String missionRoleCode, LocalDate validFrom, LocalDate validTo) {
    }

    record CreateMissionCommand(UUID tenantId, UUID actorUserId, String title, String missionTypeCode, UUID unitId,
            UUID auditPlanItemId, List<UUID> auditedEntityIds, String scope, LocalDate plannedStart,
            LocalDate plannedEnd, BigDecimal budgetDays, List<TeamMemberCommand> team) {
    }

    record CreatedMission(MissionView mission, String scope, List<EntityRef> auditedEntities) {
    }

    record CreateEntityCommand(UUID tenantId, UUID actorUserId, String code, String label, String kind, UUID unitId,
            UUID ownerUserId, List<String> riskFamilyCodes, String regulatoryRequirement) {
    }

    /** Types de mission actifs du tenant (pack/paramétrage). Réservé à mission.read / mission.create. */
    List<MissionTypeView> activeMissionTypes(UUID tenantId, UUID userId);

    /** Univers d'audit (réservé à universe.read), filtré et paginé. */
    EntityPage listAuditableEntities(UUID tenantId, UUID userId, String query, String kind, UUID unitId, int page,
            int size);

    Optional<AuditableEntityView> findAuditableEntity(UUID tenantId, UUID userId, UUID id);

    /**
     * @throws ForbiddenException      droit universe.manage absent (403)
     * @throws InvalidRequestException unité inconnue (422)
     * @throws ConflictException       code déjà utilisé (409)
     */
    AuditableEntityView createAuditableEntity(CreateEntityCommand command);

    /**
     * Crée la mission : droit mission.create sur l'unité, type actif, entités auditables du tenant,
     * dates cohérentes, équipe (rôles de mission connus). Journalisée.
     *
     * @throws ForbiddenException      pas de mission.create sur l'unité (403)
     * @throws InvalidRequestException référence inconnue ou dates incohérentes (422)
     * @throws ConflictException       référence de mission en collision (409)
     */
    CreatedMission createMission(CreateMissionCommand command);

    class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
