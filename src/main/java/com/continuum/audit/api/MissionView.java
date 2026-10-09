package com.continuum.audit.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Projection lecture d'une mission (contrat {@code app/missions.yaml}, §6). Les libellés de
 * référentiels (type, statut, phase) sont portés inline en attendant le module de paramétrage (§1.6).
 */
public record MissionView(
        UUID id,
        String reference,
        String title,
        String missionTypeCode,
        String missionTypeLabel,
        String statusCode,
        String statusLabel,
        String phaseCode,
        String phaseLabel,
        Integer progressPercent,
        LocalDate plannedStart,
        LocalDate plannedEnd,
        UUID unitId,
        String unitCode,
        String unitLabel,
        OffsetDateTime createdAt,
        int version) {
}
