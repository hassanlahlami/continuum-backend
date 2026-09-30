package com.continuum.iam.web;

import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import gen.app.me.api.MeApi;
import gen.app.me.model.Permissions;
import gen.app.me.model.PermissionsFeaturesInner;
import gen.app.me.model.PermissionsFeaturesInnerScopesInner;
import gen.app.me.model.PermissionsFeaturesInnerScopesInner.TypeEnum;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implémente {@code GET /me/permissions} (contrat {@code app/me.yaml}, §2.1.7) : les droits effectifs
 * de l'utilisateur, par portée — base de l'affichage des menus/boutons côté front (D32).
 *
 * <p>Le contrôleur implémente l'interface <strong>générée</strong> depuis le contrat ; il ne fait que
 * projeter le read model {@link AuthorizationService#permissions}. Préfixe {@code /api/app} (§4.3.3).
 */
@RestController
@RequestMapping("/api/app")
public class MePermissionsController implements MeApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";
    private static final String CLAIM_AUTHZ_VERSION = "authz_version";

    private final AuthorizationService authorization;

    public MePermissionsController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public ResponseEntity<Permissions> getMyPermissions() {
        Jwt jwt = currentJwt();
        UUID tenant = UUID.fromString(jwt.getClaimAsString(CLAIM_TENANT));
        UUID user = UUID.fromString(jwt.getClaimAsString(CLAIM_USER));

        Map<String, List<ScopedFeature>> byCode = authorization.permissions(tenant, user).stream()
                .collect(Collectors.groupingBy(ScopedFeature::featureCode));

        List<PermissionsFeaturesInner> features = byCode.entrySet().stream()
                .map(entry -> {
                    List<PermissionsFeaturesInnerScopesInner> scopes = entry.getValue().stream()
                            .map(sf -> new PermissionsFeaturesInnerScopesInner(TypeEnum.fromValue(sf.scopeType()))
                                    .id(sf.scopeId()))
                            .toList();
                    return new PermissionsFeaturesInner(entry.getKey(), scopes);
                })
                .toList();

        return ResponseEntity.ok(new Permissions(authzVersion(jwt), features));
    }

    private static int authzVersion(Jwt jwt) {
        Object claim = jwt.getClaim(CLAIM_AUTHZ_VERSION);
        return claim instanceof Number n ? n.intValue() : 0;
    }

    private static Jwt currentJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        return jwt;
    }
}
