package com.continuum.audit.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Projection lecture d'un constat (contrat {@code app/findings.yaml}, §6). Libellés de référentiels
 * (statut, criticité) inline en attendant le module de paramétrage (§1.6).
 */
public record FindingView(
        UUID id,
        String reference,
        String title,
        String statusCode,
        String statusLabel,
        String criticalityCode,
        String criticalityLabel,
        String source,
        UUID missionId,
        String missionTitle,
        OffsetDateTime createdAt,
        int version) {
}
