package com.continuum.audit.web;

import com.continuum.audit.api.MissionPlanningService;
import com.continuum.audit.api.MissionPlanningService.AuditableEntityView;
import com.continuum.audit.api.MissionPlanningService.ConflictException;
import com.continuum.audit.api.MissionPlanningService.CreateEntityCommand;
import com.continuum.audit.api.MissionPlanningService.EntityPage;
import com.continuum.audit.api.MissionPlanningService.ForbiddenException;
import com.continuum.audit.api.MissionPlanningService.InvalidRequestException;
import gen.app.planning.api.UniverseApi;
import gen.app.planning.model.AuditMeta;
import gen.app.planning.model.AuditableEntity;
import gen.app.planning.model.AuditableEntityInput;
import gen.app.planning.model.AuditableEntityPage;
import gen.app.planning.model.UnitSummary;
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
 * Univers d'audit (contrat {@code app/planning.yaml}, tag {@code universe}) : liste, détail et création des
 * entités auditables. Implémente l'interface <strong>générée</strong> (D32) ; droits {@code universe.read} /
 * {@code universe.manage} contrôlés par {@link MissionPlanningService}. Mise à jour et couverture : non câblées
 * (501 par défaut de l'interface générée).
 */
@RestController
@RequestMapping("/api/app")
public class UniverseController implements UniverseApi {

    private final MissionPlanningService planning;

    public UniverseController(MissionPlanningService planning) {
        this.planning = planning;
    }

    @Override
    public ResponseEntity<AuditableEntityPage> listAuditableEntities(Integer page, Integer size, List<String> sort,
            String q, UUID unitId, String kind, String riskLevel, Integer notAuditedSinceYears) {
        Jwt jwt = JwtClaims.current();
        int p = page == null ? 0 : page;
        int s = size == null ? 25 : size;
        EntityPage result = guard(() -> planning.listAuditableEntities(JwtClaims.tenant(jwt), JwtClaims.user(jwt),
                q, kind, unitId, p, s));
        int totalPages = (int) Math.ceil(result.totalItems() / (double) s);
        return ResponseEntity.ok(new AuditableEntityPage(p, s, result.totalItems(), totalPages,
                result.items().stream().map(UniverseController::toModel).toList()));
    }

    @Override
    public ResponseEntity<AuditableEntity> getAuditableEntity(UUID auditableEntityId) {
        Jwt jwt = JwtClaims.current();
        return guard(() -> planning.findAuditableEntity(JwtClaims.tenant(jwt), JwtClaims.user(jwt),
                auditableEntityId))
                .map(UniverseController::toModel)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entité auditable inconnue"));
    }

    @Override
    public ResponseEntity<AuditableEntity> createAuditableEntity(AuditableEntityInput in) {
        Jwt jwt = JwtClaims.current();
        AuditableEntityView created = guard(() -> planning.createAuditableEntity(new CreateEntityCommand(
                JwtClaims.tenant(jwt), JwtClaims.user(jwt), in.getCode(), in.getLabel(), in.getKind().getValue(),
                in.getUnitId(), in.getOwnerUserId(), in.getRiskFamilyCodes(), in.getRegulatoryRequirement())));
        return ResponseEntity.status(HttpStatus.CREATED).body(toModel(created));
    }

    static <T> T guard(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (ForbiddenException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (InvalidRequestException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (ConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static AuditableEntity toModel(AuditableEntityView v) {
        AuditableEntity e = new AuditableEntity(v.label(), AuditableEntity.KindEnum.fromValue(v.kind()), v.id(),
                new AuditMeta(v.createdAt(), v.version()))
                .code(v.code())
                .version(v.version())
                .regulatoryRequirement(v.regulatoryRequirement())
                .riskFamilyCodes(v.riskFamilyCodes());
        e.setUnitId(v.unitId());
        e.setOwnerUserId(v.ownerUserId());
        if (v.unitId() != null) {
            e.unit(new UnitSummary(v.unitId(), v.unitLabel() == null ? v.unitCode() : v.unitLabel())
                    .code(v.unitCode()));
        }
        return e;
    }
}
