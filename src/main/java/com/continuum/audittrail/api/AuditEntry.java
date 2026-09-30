package com.continuum.audittrail.api;

import java.util.UUID;

/**
 * Entrée à journaliser (plan §2.3) : qui / quoi / sur quel objet, avec l'état avant-après en JSON.
 * L'horodatage et le chaînage sont ajoutés par {@link AuditTrailService}.
 *
 * @param tenantId    entité concernée
 * @param actorUserId auteur de l'action (peut être {@code null} pour une action système)
 * @param action      code d'action / de fonctionnalité (ex. {@code finding.validate})
 * @param objectType  type d'objet visé (ex. {@code FINDING}), optionnel
 * @param objectId    identifiant de l'objet visé, optionnel
 * @param payload     détail (avant/après) en JSON, stocké verbatim pour l'intégrité, optionnel
 */
public record AuditEntry(
        UUID tenantId,
        UUID actorUserId,
        String action,
        String objectType,
        String objectId,
        String payload) {
}
