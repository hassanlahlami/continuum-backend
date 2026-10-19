package com.continuum;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Harmonise toutes les réponses d'erreur de l'API en <strong>RFC 9457 {@code application/problem+json}</strong>
 * (contrat source de vérité, D32 — {@code common.yaml#/components/schemas/Problem}). Le schéma exige au
 * minimum {@code title}, {@code status} et {@code traceId} ; Spring produit nativement
 * title/status/detail/instance pour les exceptions MVC et {@code ResponseStatusException}, et cette
 * classe ajoute le {@code traceId} (référence d'erreur affichée à l'utilisateur, §10.8) manquant.
 *
 * <p>Couvre : les {@code ResponseStatusException} levées par les contrôleurs et les exceptions MVC de
 * Spring (via {@link ResponseEntityExceptionHandler#handleExceptionInternal}), plus un filet de sécurité
 * pour toute exception imprévue (⇒ 500 {@code problem+json}, jamais de stacktrace exposée).
 *
 * <p>Les refus de sécurité de méthode ({@code @PreAuthorize}) et d'authentification sont traités en amont
 * par la chaîne Spring Security (filtres), hors du périmètre de cet {@code @ControllerAdvice}.
 */
@RestControllerAdvice
public class ProblemDetailAdvice extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            withTraceId(pd);
        }
        return response;
    }

    /** Filet de sécurité : toute exception non prévue devient un {@code problem+json} 500 enrichi. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Erreur interne");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(withTraceId(pd));
    }

    /** Ajoute le {@code traceId} requis (id de trace courant s'il existe, sinon référence générée). */
    private static ProblemDetail withTraceId(ProblemDetail pd) {
        if (pd.getProperties() == null || !pd.getProperties().containsKey("traceId")) {
            pd.setProperty("traceId", ErrorReference.traceId());
        }
        return pd;
    }
}
