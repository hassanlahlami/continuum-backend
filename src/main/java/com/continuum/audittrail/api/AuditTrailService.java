package com.continuum.audittrail.api;

import java.util.UUID;

/**
 * Journal d'audit immuable (plan §2.3) : chaînage + signature HMAC rendant toute altération
 * détectable. Les moteurs sont backend-first (D32).
 */
public interface AuditTrailService {

    /**
     * Journalise une entrée et renvoie son numéro d'ordre ({@code seq}) dans la chaîne du tenant.
     */
    long append(AuditEntry entry);

    /**
     * Recalcule la chaîne et les signatures du tenant pour détecter une altération.
     */
    AuditVerification verify(UUID tenantId);

    /**
     * L'utilisateur a-t-il déjà réalisé {@code action} sur cet objet ? Utilisé notamment par la SoD
     * dynamique (§2.2) pour interdire une action en conflit sur le même objet.
     */
    boolean hasActed(UUID tenantId, UUID actorUserId, String action, String objectType, String objectId);
}
