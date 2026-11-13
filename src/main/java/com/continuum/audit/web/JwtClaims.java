package com.continuum.audit.web;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Accès au jeton courant (tenant et utilisateur viennent toujours du JWT, jamais de la requête). */
final class JwtClaims {

    private JwtClaims() {
    }

    static Jwt current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        return jwt;
    }

    static UUID tenant(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }

    static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("continuum_user_id"));
    }
}
