package com.continuum.tenancy.web;

import com.continuum.tenancy.api.EntityActionService;
import com.continuum.tenancy.api.EntityActionService.EntityActionCommand;
import com.continuum.tenancy.api.EntityActionService.EntityView;
import com.continuum.tenancy.api.EntityActionService.IllegalEntityTransitionException;
import com.continuum.tenancy.api.EntityActionService.StaleEntityException;
import com.continuum.tenancy.api.EntityActionService.UnknownEntityException;
import gen.admin.iam.api.EntitiesApi;
import gen.admin.iam.model.ActionRequest;
import gen.admin.iam.model.AvailableAction;
import gen.admin.iam.model.Entity;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cycle de vie des entités côté Admin Système (contrat {@code admin/tenancy-iam.yaml}, §4.7). Implémente
 * l'interface <strong>générée</strong> (D32) ; seule {@code performEntityAction} est surchargée ici. Le tenant
 * de l'acteur vient du jeton, jamais de la requête.
 */
@RestController
@RequestMapping("/api/admin")
public class EntitiesController implements EntitiesApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";

    private final EntityActionService actions;

    public EntitiesController(EntityActionService actions) {
        this.actions = actions;
    }

    @Override
    @PreAuthorize("@authz.can('admin.entity.manage')")
    public ResponseEntity<Entity> performEntityAction(UUID entityId, String actionCode, ActionRequest actionRequest) {
        Jwt jwt = currentJwt();
        UUID tenant = UUID.fromString(jwt.getClaimAsString(CLAIM_TENANT));
        UUID user = UUID.fromString(jwt.getClaimAsString(CLAIM_USER));
        try {
            EntityView view = actions.perform(new EntityActionCommand(tenant, user, entityId, actionCode,
                    actionRequest == null ? null : actionRequest.getVersion()));
            return ResponseEntity.ok(toModel(view));
        } catch (UnknownEntityException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IllegalEntityTransitionException | StaleEntityException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static Entity toModel(EntityView v) {
        // L'accès à l'opération exige déjà admin.entity.manage (@PreAuthorize) : les actions sont proposées.
        List<AvailableAction> available = EntityActionService.actionsFrom(v.status()).stream().map(EntitiesController::action).toList();
        return new Entity(v.code(), v.label(), v.id(), Entity.StatusEnum.fromValue(v.status()))
                .version(v.version())
                .availableActions(available);
    }

    private static AvailableAction action(String code) {
        return switch (code) {
            case EntityActionService.ACTION_SUSPEND -> new AvailableAction(code, "Suspendre")
                    .style(AvailableAction.StyleEnum.DANGER)
                    .confirmationMessage("Les utilisateurs de l'entité ne pourront plus se connecter.");
            case EntityActionService.ACTION_REACTIVATE -> new AvailableAction(code, "Réactiver")
                    .style(AvailableAction.StyleEnum.PRIMARY);
            default -> new AvailableAction(code, "Passer en maintenance");
        };
    }

    private static Jwt currentJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        return jwt;
    }
}
