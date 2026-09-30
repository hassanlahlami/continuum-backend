package com.continuum.iam.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Affectation active d'un utilisateur (plan §2.1.3), pour l'affichage (ex. {@code /me}).
 *
 * @param positionCode  code de la position occupée
 * @param positionLabel libellé de la position (locale de résolution)
 * @param unitId        unité de rattachement ({@code null} pour une position de niveau tenant)
 * @param unitCode      code de l'unité ({@code null} si niveau tenant)
 * @param kind          {@code HOLDER}, {@code INTERIM} ou {@code DELEGATION}
 * @param validTo       fin de validité ({@code null} = sans terme)
 */
public record AssignmentView(
        String positionCode,
        String positionLabel,
        UUID unitId,
        String unitCode,
        String kind,
        LocalDate validTo) {
}
