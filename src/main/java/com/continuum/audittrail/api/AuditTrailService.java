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
}
