package com.continuum;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Fabrique le {@code traceId} des réponses d'erreur {@code application/problem+json} (RFC 9457, requis
 * par le contrat — §10.8) : l'identifiant de trace courant s'il existe (corrélation avec les logs ECS),
 * sinon une référence compacte générée. Partagé par {@link ProblemDetailAdvice} (erreurs MVC) et
 * {@link ProblemSecurityHandler} (401/403 de la chaîne de sécurité).
 */
final class ErrorReference {

    private ErrorReference() {
    }

    static String traceId() {
        String traceId = MDC.get("traceId");
        return (traceId != null && !traceId.isBlank())
                ? traceId
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
