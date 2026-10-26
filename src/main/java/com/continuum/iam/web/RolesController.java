package com.continuum.iam.web;

import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.CreateRoleCommand;
import com.continuum.iam.api.Feature;
import com.continuum.iam.api.FeatureCatalog;
import com.continuum.iam.api.RoleDirectory;
import com.continuum.iam.api.RoleService;
import com.continuum.iam.api.RoleView;
import com.continuum.iam.api.SodRuleDirectory;
import com.continuum.iam.api.SodRuleView;
import gen.admin.iam.api.RolesApi;
import gen.admin.iam.model.AvailableAction;
import gen.admin.iam.model.LocalizedText;
import gen.admin.iam.model.Role;
import gen.admin.iam.model.RoleFeatureMatrix;
import gen.admin.iam.model.RoleFeatureMatrixGrantsInner;
import gen.admin.iam.model.RoleFeatureMatrixSodWarningsInner;
import gen.admin.iam.model.RoleInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestion des rôles côté Admin Système (contrat {@code admin/tenancy-iam.yaml}, §2.1.1). Implémente
 * l'interface <strong>générée</strong> (D32) et délègue à {@link RoleService}. Le rôle est créé
 * <em>sans</em> fonctionnalités : celles-ci se gèrent via la matrice rôle↔fonctionnalité
 * ({@code /role-feature-matrix}). L'auteur vient du jeton.
 */
@RestController
@RequestMapping("/api/admin")
public class RolesController implements RolesApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";

    /** Version de la matrice : la gestion de version optimiste arrive avec l'écriture différentielle. */
    private static final int MATRIX_VERSION = 1;

    /** Action de la matrice : décidée ici par le serveur (droit {@code admin.role.manage}), jamais par le front. */
    static final String ACTION_UPDATE_MATRIX = "update-matrix";

    private final RoleService roles;
    private final RoleDirectory roleDirectory;
    private final SodRuleDirectory sodRules;
    private final AuthorizationService authorization;

    public RolesController(RoleService roles, RoleDirectory roleDirectory, SodRuleDirectory sodRules,
            AuthorizationService authorization) {
        this.roles = roles;
        this.roleDirectory = roleDirectory;
        this.sodRules = sodRules;
        this.authorization = authorization;
    }

    @Override
    @PreAuthorize("@authz.can('admin.role.read')")
    public ResponseEntity<List<Role>> listRoles() {
        return ResponseEntity.ok(roleDirectory.listRoles(tenant()).stream().map(RolesController::toModel).toList());
    }

    @Override
    @PreAuthorize("@authz.can('admin.role.read')")
    public ResponseEntity<List<gen.admin.iam.model.Feature>> listFeatures() {
        return ResponseEntity.ok(FeatureCatalog.all().stream().map(RolesController::toModel).toList());
    }

    @Override
    @PreAuthorize("@authz.can('admin.role.read')")
    public ResponseEntity<RoleFeatureMatrix> getRoleFeatureMatrix() {
        UUID tenant = tenant();
        List<RoleView> views = roleDirectory.listRoles(tenant);
        List<RoleFeatureMatrixGrantsInner> grants = new ArrayList<>();
        for (RoleView r : views) {
            for (String code : r.featureCodes()) {
                grants.add(new RoleFeatureMatrixGrantsInner().roleId(r.id()).featureCode(code));
            }
        }
        List<RoleFeatureMatrixSodWarningsInner> warnings = new ArrayList<>();
        for (SodRuleView rule : sodRules.listRules(tenant)) {
            if (!"STATIC".equals(rule.type())) {
                continue;
            }
            for (RoleView r : views) {
                Set<String> held = new HashSet<>(r.featureCodes());
                if (held.contains(rule.featureA()) && held.contains(rule.featureB())) {
                    warnings.add(new RoleFeatureMatrixSodWarningsInner().roleId(r.id()).ruleId(rule.id())
                            .message(rule.featureA() + " / " + rule.featureB()));
                }
            }
        }
        RoleFeatureMatrix body = new RoleFeatureMatrix(MATRIX_VERSION,
                views.stream().map(RolesController::toModel).toList(),
                FeatureCatalog.all().stream().map(RolesController::toModel).toList(),
                grants)
                .sodWarnings(warnings)
                .availableActions(matrixActions(tenant, uuidClaim(currentJwt(), CLAIM_USER)));
        return ResponseEntity.ok(body);
    }

    /** Actions présentables sur la matrice pour l'utilisateur courant (contrat {@code availableActions}). */
    private List<AvailableAction> matrixActions(UUID tenant, UUID actor) {
        boolean canManage = authorization.permissions(tenant, actor).stream()
                .anyMatch(f -> "admin.role.manage".equals(f.featureCode()));
        return canManage
                ? List.of(new AvailableAction(ACTION_UPDATE_MATRIX, "Enregistrer la matrice")
                        .style(AvailableAction.StyleEnum.PRIMARY))
                : List.of();
    }

    private static Role toModel(RoleView v) {
        LocalizedText label = new LocalizedText(v.label().get("fr"))
                .ar(v.label().get("ar")).en(v.label().get("en"));
        return new Role(v.code(), label, Role.KindEnum.fromValue(v.kind()), v.id())
                .fromPack(v.fromPack()).featureCodes(v.featureCodes()).positionsCount(v.positionsCount());
    }

    private static gen.admin.iam.model.Feature toModel(Feature f) {
        return new gen.admin.iam.model.Feature(f.code(), f.module().name(), f.labelKey())
                .scopable(f.scopable()).sensitive(f.sensitive()).deprecated(f.deprecated());
    }

    private static UUID tenant() {
        return uuidClaim(currentJwt(), CLAIM_TENANT);
    }

    @Override
    @PreAuthorize("@authz.can('admin.role.manage')")
    public ResponseEntity<Role> createRole(RoleInput input) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID actor = uuidClaim(jwt, CLAIM_USER);

        UUID id = roles.create(new CreateRoleCommand(
                tenant, actor, input.getCode(), input.getLabel().getFr(),
                input.getKind().getValue(), List.of()));

        Role body = new Role(input.getCode(), input.getLabel(),
                Role.KindEnum.fromValue(input.getKind().getValue()), id);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
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
