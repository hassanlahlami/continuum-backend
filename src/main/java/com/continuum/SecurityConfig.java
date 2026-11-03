package com.continuum;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Sécurité de l'API partagée (plan §4.3.3) : deux chaînes distinctes sur le même jar.
 *
 * <ul>
 *   <li>{@code /api/admin/**} : jeton d'audience {@code continuum-admin} <strong>et</strong> niveau
 *       d'authentification élevé ({@code acr} ≥ 2, MFA forte) ;</li>
 *   <li>{@code /api/app/**} : jeton d'audience {@code continuum-app} ;</li>
 *   <li>{@code /internal/**} : appels service-a-service du SPI Keycloak (client credentials, {@code azp}) ;</li>
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

    /** CORS pour les fronts (origines configurables, {@code continuum.cors.allowed-origins}). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${continuum.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    @Order(1)
    @ConditionalOnExpression(DeploymentSurface.ON_ADMIN)
    SecurityFilterChain adminApi(HttpSecurity http, ProblemSecurityHandler problem) throws Exception {
        http
                .securityMatcher("/api/admin/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().access(hasAudienceAndAcr(AUDIENCE_ADMIN, ADMIN_MIN_ACR)))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problem).accessDeniedHandler(problem))
                .exceptionHandling(e -> e.authenticationEntryPoint(problem).accessDeniedHandler(problem));
        return http.build();
    }

    @Bean
    @Order(2)
    @ConditionalOnExpression(DeploymentSurface.ON_APP)
    SecurityFilterChain appApi(HttpSecurity http, ProblemSecurityHandler problem) throws Exception {
        http
                .securityMatcher("/api/app/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().access(hasAudienceAndAcr(AUDIENCE_APP, 0)))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problem).accessDeniedHandler(problem))
                .exceptionHandling(e -> e.authenticationEntryPoint(problem).accessDeniedHandler(problem));
        return http.build();
    }

    /**
     * API interne service-a-service (SPI Keycloak, {@code internal/keycloak.yaml}) : jeton
     * <em>client credentials</em> dont l'{@code azp} est le client de service configure
     * ({@code continuum.internal.client-id}). Jeton absent/invalide : 401 ; autre client : 403.
     */
    /** Double-submit CSRF cookie (XSRF-TOKEN, readable by the SPA) for the BFF auth endpoints (ADR 0014). */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        return repo;
    }

    /**
     * BFF auth endpoints {@code /api/auth/**} (contrat {@code public/auth.yaml}) : accessibles sans jeton
     * (la session est portée par le cookie {@code CT_SESSION}, vérifiée par le contrôleur), protégés par
     * CSRF double-submit ({@code csrf.spa()}), même origine donc sans CORS.
     */
    @Bean
    @Order(-1)
    SecurityFilterChain authBff(HttpSecurity http, CookieCsrfTokenRepository csrfRepo,
            ProblemSecurityHandler problem) throws Exception {
        http
                .securityMatcher("/api/auth/**")
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepo)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .exceptionHandling(e -> e.accessDeniedHandler(problem));
        return http.build();
    }

    /** Force la génération du cookie XSRF-TOKEN (jeton différé en mode SPA) sur chaque requête. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    @Bean
    @Order(0)
    SecurityFilterChain internalApi(HttpSecurity http, ProblemSecurityHandler problem,
            @Value("${continuum.internal.client-id:continuum-keycloak}") String clientId) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().access(hasAuthorizedParty(clientId)))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problem).accessDeniedHandler(problem))
                .exceptionHandling(e -> e.authenticationEntryPoint(problem).accessDeniedHandler(problem));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultApi(HttpSecurity http, ProblemSecurityHandler problem) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/api/public/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problem).accessDeniedHandler(problem))
                .exceptionHandling(e -> e.authenticationEntryPoint(problem).accessDeniedHandler(problem));
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

    /** Exige un JWT emis pour le client {@code azp} donne (compte de service, sans utilisateur). */
    private static AuthorizationManager<RequestAuthorizationContext> hasAuthorizedParty(String clientId) {
        return (Supplier<Authentication> authentication, RequestAuthorizationContext context) -> {
            Authentication a = authentication.get();
            boolean granted = a != null
                    && a.isAuthenticated()
                    && a.getPrincipal() instanceof Jwt jwt
                    && clientId.equals(jwt.getClaimAsString("azp"));
            return new AuthorizationDecision(granted);
        };
    }

    static int acrOf(Jwt jwt) {
        return acr(jwt);
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
