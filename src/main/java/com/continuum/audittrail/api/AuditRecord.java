package com.continuum.audittrail.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Une entrée du journal d'audit exportée (plan §2.3), pour la remise aux auditeurs externes.
 *
 * @param seq         numéro d'ordre dans la chaîne du tenant
 * @param occurredAt  horodatage
 * @param actorUserId auteur ({@code null} pour une action système)
 * @param action      code d'action
 * @param objectType  type d'objet visé
 * @param objectId    identifiant de l'objet visé
 * @param payload     détail (avant/après) en JSON
 * @param entryHash   signature HMAC chaînée de l'entrée
 */
public record AuditRecord(
        long seq,
        Instant occurredAt,
        UUID actorUserId,
        String action,
        String objectType,
        String objectId,
        String payload,
        String entryHash) {
}
