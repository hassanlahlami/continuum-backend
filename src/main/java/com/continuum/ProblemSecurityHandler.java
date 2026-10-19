package com.continuum;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
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
        write(response, HttpStatus.UNAUTHORIZED, "Authentification requise");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Accès refusé");
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
