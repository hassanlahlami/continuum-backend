package com.continuum.audittrail.api;

/**
 * Résultat d'une vérification d'intégrité du journal (plan §2.3).
 *
 * @param intact         vrai si la chaîne et toutes les signatures sont valides
 * @param firstBrokenSeq numéro de la première entrée altérée ({@code null} si intact)
 * @param count          nombre d'entrées vérifiées
 */
public record AuditVerification(boolean intact, Long firstBrokenSeq, long count) {
}
