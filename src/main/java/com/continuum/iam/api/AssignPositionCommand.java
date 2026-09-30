package com.continuum.iam.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Demande d'affectation d'une position à un utilisateur (plan §2.1.3, §2.1.8).
 *
 * @param tenantId     entité
 * @param actorUserId  auteur de l'octroi (maker) — ne peut pas être le bénéficiaire (anti-auto-octroi)
 * @param targetUserId bénéficiaire
 * @param positionId   position octroyée
 * @param orgUnitId    unité de rattachement ({@code null} pour une position de niveau tenant)
 * @param kind         {@code HOLDER}, {@code INTERIM} ou {@code DELEGATION}
 * @param validFrom    début de validité ({@code null} = aujourd'hui)
 */
public record AssignPositionCommand(
        UUID tenantId,
        UUID actorUserId,
        UUID targetUserId,
        UUID positionId,
        UUID orgUnitId,
        String kind,
        LocalDate validFrom) {
}
