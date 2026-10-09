package com.continuum.iam.internal;

import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.SodViolation;
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
    private final SegregationOfDutiesService sod;

    public AuthzExpression(AuthorizationService authorization, SegregationOfDutiesService sod) {
        this.authorization = authorization;
        this.sod = sod;
    }

    /** Droit sur une unité (§2.1.4). */
    public boolean can(String featureCode, UUID unitId) {
        Jwt jwt = currentJwt();
        return authorization.can(tenantId(jwt), userId(jwt), featureCode, unitId);
    }

    /**
     * Droit à l'échelle de l'entité (§2.1.4) : vrai si l'utilisateur détient {@code featureCode} par au
     * moins une affectation, quelle qu'en soit la portée. Variante sans unité pour les ressources
     * <em>tenant-wide</em> (ex. création de rôle, §2.1.1) où aucune unité ne qualifie la ressource.
     */
    public boolean can(String featureCode) {
        Jwt jwt = currentJwt();
        return authorization.permissions(tenantId(jwt), userId(jwt)).stream()
                .anyMatch(f -> featureCode.equals(f.featureCode()));
    }

    /** Droit contextuel de mission (§2.1.1). */
    public boolean canOnMission(String featureCode, UUID missionId) {
        Jwt jwt = currentJwt();
        return authorization.canOnMission(tenantId(jwt), userId(jwt), featureCode, missionId);
    }

    /**
     * Droit sur une unité <strong>et</strong> respect de la SoD dynamique sur l'objet visé (§2.1.4,
     * point 4). Faux si {@code can} refuse ou si une règle SoD dynamique {@code BLOCK} s'applique
     * (ex. l'auteur d'un constat qui tente de le valider).
     */
    public boolean canDo(String featureCode, UUID unitId, String objectType, String objectId) {
        Jwt jwt = currentJwt();
        UUID tenant = tenantId(jwt);
        UUID user = userId(jwt);
        if (!authorization.can(tenant, user, featureCode, unitId)) {
            return false;
        }
        return sod.dynamicConflicts(tenant, user, featureCode, objectType, objectId).stream()
                .noneMatch(v -> v.severity() == SodViolation.Severity.BLOCK);
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
