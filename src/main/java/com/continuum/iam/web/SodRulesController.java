package com.continuum.iam.web;

import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.SodRuleDirectory;
import com.continuum.iam.api.SodRuleView;
import gen.admin.iam.api.SodApi;
import gen.admin.iam.model.AvailableAction;
import gen.admin.iam.model.SodRule;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consultation des règles de séparation des tâches côté Admin Système (contrat
 * {@code admin/tenancy-iam.yaml}, §2.2). Implémente l'interface <strong>générée</strong> (D32) et
 * délègue au read model {@link SodRuleDirectory}. Le tenant vient du jeton, jamais de la requête.
 * Seule {@code listSodRules} est surchargée ici ; les opérations d'écriture (create/update/delete)
 * seront ajoutées avec leur double validation (ADM-14).
 */
@RestController
@RequestMapping("/api/admin")
public class SodRulesController implements SodApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";
    private static final String FEATURE_MANAGE = "admin.sod.manage";
    static final String ACTION_EDIT = "edit";
    static final String ACTION_DELETE = "delete";

    private final SodRuleDirectory sodRules;
    private final AuthorizationService authorization;

    public SodRulesController(SodRuleDirectory sodRules, AuthorizationService authorization) {
        this.sodRules = sodRules;
        this.authorization = authorization;
    }

    @Override
    public ResponseEntity<List<SodRule>> listSodRules() {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        boolean canManage = authorization.permissions(tenant, uuidClaim(jwt, CLAIM_USER)).stream()
                .anyMatch(f -> FEATURE_MANAGE.equals(f.featureCode()));
        List<SodRule> body = sodRules.listRules(tenant).stream()
                .map(v -> toModel(v, canManage)).toList();
        return ResponseEntity.ok(body);
    }

    /** Une règle du pack se modifie mais ne se supprime pas ; sans droit de gestion, aucune action. */
    private static SodRule toModel(SodRuleView v, boolean canManage) {
        List<AvailableAction> actions = !canManage ? List.of()
                : v.fromPack() ? List.of(new AvailableAction(ACTION_EDIT, "Modifier"))
                : List.of(new AvailableAction(ACTION_EDIT, "Modifier"),
                        new AvailableAction(ACTION_DELETE, "Supprimer")
                                .style(AvailableAction.StyleEnum.DANGER));
        return new SodRule(
                v.featureA(),
                v.featureB(),
                SodRule.TypeEnum.fromValue(v.type()),
                SodRule.SeverityEnum.fromValue(v.severity()))
                .id(v.id())
                .fromPack(v.fromPack())
                .availableActions(actions);
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
