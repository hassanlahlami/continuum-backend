package com.continuum;

import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Sécurité de l'API partagée (plan §4.3.3) : deux chaînes distinctes sur le même jar.
 *
 * <ul>
 *   <li>{@code /api/admin/**} : jeton d'audience {@code continuum-admin} <strong>et</strong> niveau
 *       d'authentification élevé ({@code acr} ≥ 2, MFA forte) ;</li>
 *   <li>{@code /api/app/**} : jeton d'audience {@code continuum-app} ;</li>
 *   <li>reste : {@code /actuator/health|info} et {@code /api/public/**} ouverts, sinon authentifié.</li>
 * </ul>
 *
 * <p>Le filtrage réseau (n'exposer {@code /api/admin/**} que depuis le réseau d'administration) est
 * fait à l'ingress, hors de ce code. La sécurité de méthode {@code @PreAuthorize("@authz.can(...)")}
 * (§2.1.7) s'ajoute par-dessus ces portes grossières.
 *
 * <p><strong>Surface de déploiement</strong> ({@link DeploymentSurface}, §4.3.2) : la chaîne admin
 * n'existe que sur la surface {@code admin} ou {@code all}, la chaîne app que sur {@code app} ou
 * {@code all}. La chaîne par défaut (actuator/public + authentifié) reste toujours présente — y compris
 * sur un {@code worker} — pour les sondes de santé.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    static final String AUDIENCE_ADMIN = "continuum-admin";
    static final String AUDIENCE_APP = "continuum-app";
    static final int ADMIN_MIN_ACR = 2;

    @Bean
    @Order(1)
    @ConditionalOnExpression(DeploymentSurface.ON_ADMIN)
    SecurityFilterChain adminApi(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/admin/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().access(hasAudienceAndAcr(AUDIENCE_ADMIN, ADMIN_MIN_ACR)))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    @ConditionalOnExpression(DeploymentSurface.ON_APP)
    SecurityFilterChain appApi(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/app/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().access(hasAudienceAndAcr(AUDIENCE_APP, 0)))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultApi(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/api/public/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Exige un JWT dont l'audience contient {@code audience} et dont {@code acr} ≥ {@code minAcr}. */
    private static AuthorizationManager<RequestAuthorizationContext> hasAudienceAndAcr(String audience, int minAcr) {
        return (Supplier<Authentication> authentication, RequestAuthorizationContext context) -> {
            Authentication a = authentication.get();
            boolean granted = a != null
                    && a.isAuthenticated()
                    && a.getPrincipal() instanceof Jwt jwt
                    && jwt.getAudience() != null
                    && jwt.getAudience().contains(audience)
                    && acr(jwt) >= minAcr;
            return new AuthorizationDecision(granted);
        };
    }

    private static int acr(Jwt jwt) {
        String value = jwt.getClaimAsString("acr");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
