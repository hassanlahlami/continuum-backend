package com.continuum.iam.internal;

import com.continuum.iam.api.AuthorizationService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Bean d'expression {@code @authz} pour {@code @PreAuthorize("@authz.can('feature.code', #id)")}
 * (plan §2.1.7).
 *
 * <p>Résout l'utilisateur et le tenant depuis le jeton JWT du contexte de sécurité (§4.5 : le token
 * porte {@code continuum_user_id} et {@code tenant_id}), puis délègue au moteur
 * {@link AuthorizationService}. Le front ne code aucune règle : il consomme {@code /me/permissions}.
 */
@Component("authz")
public class AuthzExpression {

    public static final String CLAIM_TENANT = "tenant_id";
    public static final String CLAIM_USER = "continuum_user_id";

    private final AuthorizationService authorization;

    public AuthzExpression(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /** Droit sur une unité (§2.1.4). */
    public boolean can(String featureCode, UUID unitId) {
        Jwt jwt = currentJwt();
        return authorization.can(tenantId(jwt), userId(jwt), featureCode, unitId);
    }

    /** Droit contextuel de mission (§2.1.1). */
    public boolean canOnMission(String featureCode, UUID missionId) {
        Jwt jwt = currentJwt();
        return authorization.canOnMission(tenantId(jwt), userId(jwt), featureCode, missionId);
    }

    private static Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        return jwt;
    }

    private static UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(CLAIM_TENANT));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(CLAIM_USER));
    }
}
