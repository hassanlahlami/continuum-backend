package com.continuum.iam.web;

import com.continuum.iam.api.IdentityProviderAdmin.IdentityProviderUnavailableException;
import com.continuum.iam.api.UserAccountActionService;
import com.continuum.iam.api.UserAccountActionService.IllegalUserActionException;
import com.continuum.iam.api.UserAccountActionService.StaleUserException;
import com.continuum.iam.api.UserAccountActionService.UnknownUserException;
import com.continuum.iam.api.UserAccountActionService.UserAccountView;
import com.continuum.iam.api.UserAccountActionService.UserActionCommand;
import gen.admin.iam.api.UsersApi;
import gen.admin.iam.model.ActionRequest;
import gen.admin.iam.model.AvailableAction;
import gen.admin.iam.model.UserAccount;
import java.time.ZoneOffset;
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
 * Comptes utilisateurs côté Admin Système (contrat {@code admin/tenancy-iam.yaml}, D40). Implémente l'interface
 * <strong>générée</strong> (D32) ; seule {@code performUserAction} est surchargée ici. L'acteur vient du jeton.
 */
@RestController
@RequestMapping("/api/admin")
public class UsersController implements UsersApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";

    private final UserAccountActionService actions;

    public UsersController(UserAccountActionService actions) {
        this.actions = actions;
    }

    @Override
    @PreAuthorize("@authz.can('admin.user.manage')")
    public ResponseEntity<UserAccount> performUserAction(UUID userId, String actionCode, ActionRequest request) {
        Jwt jwt = currentJwt();
        UUID tenant = UUID.fromString(jwt.getClaimAsString(CLAIM_TENANT));
        UUID actor = UUID.fromString(jwt.getClaimAsString(CLAIM_USER));
        try {
            UserAccountView view = actions.perform(new UserActionCommand(tenant, actor, userId, actionCode,
                    request == null || request.getVersion() == null ? null : request.getVersion().longValue(),
                    request == null ? null : request.getComment()));
            return ResponseEntity.ok(toModel(view, actor));
        } catch (UnknownUserException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IllegalUserActionException | StaleUserException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IdentityProviderUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private static UserAccount toModel(UserAccountView v, UUID actor) {
        List<AvailableAction> available = UserAccountActionService
                .actionsFor(v.status(), v.mfaConfigured(), v.id().equals(actor)).stream()
                .map(UsersController::action).toList();
        UserAccount m = new UserAccount(v.id(), v.username(), v.username(),
                UserAccount.SourceEnum.fromValue(v.source()), UserAccount.AccountStatusEnum.fromValue(v.status()))
                .mfaConfigured(v.mfaConfigured())
                .version((int) v.version())
                .availableActions(available);
        if (v.lastLogin() != null) {
            m.setLastLoginAt(v.lastLogin().atOffset(ZoneOffset.UTC));
        }
        return m;
    }

    private static AvailableAction action(String code) {
        return switch (code) {
            case UserAccountActionService.ACTION_LOCK -> new AvailableAction(code, "Verrouiller")
                    .style(AvailableAction.StyleEnum.DANGER).requiresComment(true)
                    .confirmationMessage("L'utilisateur ne pourra plus se connecter.");
            case UserAccountActionService.ACTION_UNLOCK -> new AvailableAction(code, "Déverrouiller")
                    .style(AvailableAction.StyleEnum.PRIMARY);
            case UserAccountActionService.ACTION_DISABLE -> new AvailableAction(code, "Désactiver")
                    .style(AvailableAction.StyleEnum.DANGER).requiresComment(true)
                    .confirmationMessage("Le compte sera désactivé.");
            case UserAccountActionService.ACTION_RESET_MFA -> new AvailableAction(code, "Réinitialiser la MFA")
                    .confirmationMessage("L'utilisateur devra enrôler un nouveau facteur à sa prochaine connexion.");
            default -> new AvailableAction(code, "Fermer toutes les sessions");
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
