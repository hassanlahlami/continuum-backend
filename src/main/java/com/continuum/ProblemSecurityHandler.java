package com.continuum;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Rend les refus de la chaîne de sécurité (plan §4.3.3) en {@code application/problem+json} (RFC 9457,
 * contrat D32), au même format que {@link ProblemDetailAdvice} pour les erreurs MVC : un
 * {@link AuthenticationEntryPoint} pour les 401 (non authentifié) et un {@link AccessDeniedHandler} pour
 * les 403 (audience/{@code acr} insuffisants, ou {@code @PreAuthorize} refusé). Chaque réponse porte
 * {@code title}/{@code status}/{@code traceId} ; aucun détail sensible n'est exposé.
 */
@Component
public class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public ProblemSecurityHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        // RFC 6750 §3 : une 401 d'une ressource protégée par jeton porteur doit annoncer le schéma.
        response.setHeader("WWW-Authenticate", "Bearer realm=\"continuum\"");
        write(response, HttpStatus.UNAUTHORIZED, "Authentification requise");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        if (insufficientAcr(request)) {
            // admin session cookie opened by /login (acr 1): step-up, not a refusal (ADR 0014 addendum b)
            response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\"");
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
            pd.setTitle("Authentification renforcée requise");
            pd.setProperty("reason", "step-up-required");
            pd.setProperty("requiredAcr", SecurityConfig.ADMIN_MIN_ACR);
            pd.setProperty("traceId", ErrorReference.traceId());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), pd);
            return;
        }
        write(response, HttpStatus.FORBIDDEN, "Accès refusé");
    }

    /** Admin API reached through the admin session cookie (no Authorization header), admin audience, acr too low. */
    private static boolean insufficientAcr(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith(request.getContextPath() + "/api/admin/")
                || request.getHeader("Authorization") != null || !hasCookie(request, com.continuum.iam.internal.bff.AuthBffProperties.ADMIN_SESSION_COOKIE)) {
            return false;
        }
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof Jwt jwt && jwt.getAudience() != null
                && jwt.getAudience().contains(SecurityConfig.AUDIENCE_ADMIN)
                && SecurityConfig.acrOf(jwt) < SecurityConfig.ADMIN_MIN_ACR;
    }

    private static boolean hasCookie(HttpServletRequest request, String name) {
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if (name.equals(c.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void write(HttpServletResponse response, HttpStatus status, String title) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(title);
        pd.setProperty("traceId", ErrorReference.traceId());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), pd);
    }
}
