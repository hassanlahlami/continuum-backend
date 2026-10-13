package com.continuum.ccm.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lecture du flux d'exceptions CCM (plan §5.3, contrat {@code app/ccm.yaml} {@code listCcmExceptions}/
 * {@code getCcmException}). Le périmètre est appliqué dans la requête (§2.1.6) : une exception n'est
 * visible que si l'utilisateur détient {@code ccm.read} sur son unité — ou, pour une exception de contrôle
 * population <strong>sans unité</strong>, à la racine du tenant (cohérent avec le triage/conversion).
 * Instancié par dialecte via {@link com.continuum.ccm.internal.CcmReadModelConfig}.
 */
public interface CcmExceptionDirectory {

    /** Projection d'une exception pour la lecture (sous-ensemble mappable vers le contrat {@code CcmException}). */
    record Row(
            UUID id,
            UUID orgUnitId,
            String unitCode,
            String unitLabel,
            String reference,
            String controlCode,
            String title,
            String statusCode,
            String statusLabel,
            String criticalityCode,
            String criticalityLabel,
            OffsetDateTime detectedAt,
            UUID assigneeUserId,
            String dismissalJustification,
            UUID findingId,
            String sourceRecordJson) {
    }

    /** Page de résultats + total (pour la pagination du contrat). */
    record Page(List<Row> items, int totalItems) {
    }

    /** Filtres du flux d'exceptions (tous optionnels ; {@code assignedToMe} se résout sur l'utilisateur courant). */
    record Filter(List<String> statusCodes, String criticality, UUID unitId, boolean assignedToMe) {
    }

    /** Une exception visible par l'utilisateur, ou vide si inconnue/hors périmètre. */
    Optional<Row> findVisible(UUID tenantId, UUID userId, UUID exceptionId);

    /** Page d'exceptions visibles filtrées (tri par date de détection décroissante). */
    Page search(UUID tenantId, UUID userId, Filter filter, int page, int size);
}
