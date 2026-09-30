package com.continuum.iam.api;

import java.util.List;
import java.util.UUID;

/**
 * Séparation des tâches (SoD) — volet <strong>statique</strong> (plan §2.1.8, §2.2) : cumul de
 * fonctionnalités interdit pour une même personne.
 *
 * <p>Sert au contrôle à l'affectation (maker-checker) et au simulateur d'habilitations. Le volet
 * <em>dynamique</em> (interdiction sur le même objet, ex. l'auteur d'un constat ne le valide pas)
 * s'appuie sur l'historique d'actions (module {@code audittrail}) et sera ajouté ensuite.
 */
public interface SegregationOfDutiesService {

    /**
     * Règles SoD statiques violées par les fonctionnalités que l'utilisateur détient sur l'unité.
     *
     * @return la liste des conflits (vide si aucun) ; {@link SodViolation.Severity#BLOCK} interdit,
     *         {@link SodViolation.Severity#WARN} à signaler
     */
    List<SodViolation> staticConflicts(UUID tenantId, UUID userId, UUID unitId);
}
