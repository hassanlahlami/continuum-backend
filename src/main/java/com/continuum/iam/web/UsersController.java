package com.continuum.iam.web;

import com.continuum.iam.api.AdminDirectory;
import com.continuum.iam.api.DirectorySyncService;
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
import gen.admin.iam.model.DirectorySyncStatus;
import gen.admin.iam.model.Job;
import gen.admin.iam.model.UserAccount;
import gen.admin.iam.model.UserPage;
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
    private final AdminDirectory directory;
    private final DirectorySyncService sync;

    public UsersController(UserAccountActionService actions, AdminDirectory directory, DirectorySyncService sync) {
        this.actions = actions;
        this.directory = directory;
        this.sync = sync;
    }

    @Override
    @PreAuthorize("@authz.can('admin.user.read')")
    public ResponseEntity<UserPage> listUsers(Integer page, Integer size, List<String> sort, String q, UUID unitId,
                                              String source, String accountStatus, UUID positionId) {
        UUID tenant = UUID.fromString(currentJwt().getClaimAsString(CLAIM_TENANT));
        int p = page == null ? 0 : page;
        int s = size == null ? 25 : size;
        AdminDirectory.UserPageView view = directory.listUsers(tenant,
                new AdminDirectory.UserQuery(q, unitId, source, accountStatus, positionId, p, s));
        UUID actor = UUID.fromString(currentJwt().getClaimAsString(CLAIM_USER));
        List<UserAccount> items = view.items().stream().map(r -> toModel(r, actor)).toList();
        int totalPages = (int) ((view.totalItems() + s - 1) / s);
        return ResponseEntity.ok(new UserPage(p, s, (int) view.totalItems(), totalPages, items));
    }

    @Override
    @PreAuthorize("@authz.can('admin.user.read')")
    public ResponseEntity<UserAccount> getUser(UUID userId) {
        UUID tenant = UUID.fromString(currentJwt().getClaimAsString(CLAIM_TENANT));
        UUID actor = UUID.fromString(currentJwt().getClaimAsString(CLAIM_USER));
        return directory.findUser(tenant, userId).map(r -> ResponseEntity.ok(toModel(r, actor)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user " + userId));
    }

    @Override
    @PreAuthorize("@authz.can('admin.user.read')")
    public ResponseEntity<DirectorySyncStatus> getDirectorySyncStatus() {
        UUID tenant = UUID.fromString(currentJwt().getClaimAsString(CLAIM_TENANT));
        DirectorySyncStatus out = new DirectorySyncStatus();
        sync.status(tenant).ifPresent(st -> {
            out.provider(st.sourceCode()).lastError(st.lastError());
            if (st.lastSyncAt() != null) {
                out.lastFullSyncAt(st.lastSyncAt().atOffset(ZoneOffset.UTC));
            }
        });
        return ResponseEntity.ok(out);
    }

    @Override
    @PreAuthorize("@authz.can('admin.user.manage')")
    public ResponseEntity<Job> triggerDirectorySync() {
        if (!sync.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Directory federation is not configured: no synchronisation source available");
        }
        UUID tenant = UUID.fromString(currentJwt().getClaimAsString(CLAIM_TENANT));
        DirectorySyncService.SyncState st = sync.trigger(tenant);
        return ResponseEntity.accepted().body(new Job().id(st.id()).type("DIRECTORY_SYNC")
                .status(Job.StatusEnum.RUNNING));
    }

    private static UserAccount toModel(AdminDirectory.UserRow r, UUID actor) {
        UserAccountView v = new UserAccountView(r.id(), null, r.username(), r.source(), r.status(),
                r.mfaConfigured(), r.lastLogin(), r.version());
        return toModel(v, actor);
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
