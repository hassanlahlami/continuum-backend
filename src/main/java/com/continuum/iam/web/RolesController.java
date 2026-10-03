package com.continuum.iam.web;

import com.continuum.iam.api.CreateRoleCommand;
import com.continuum.iam.api.RoleService;
import gen.admin.iam.api.RolesApi;
import gen.admin.iam.model.Role;
import gen.admin.iam.model.RoleInput;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    private final RoleService roles;

    public RolesController(RoleService roles) {
        this.roles = roles;
    }

    @Override
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
