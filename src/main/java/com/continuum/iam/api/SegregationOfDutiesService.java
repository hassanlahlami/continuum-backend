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

    /**
     * Règles SoD <strong>dynamiques</strong> violées si l'utilisateur exerce {@code featureCode} sur
     * l'objet ({@code objectType}/{@code objectId}) alors qu'il a déjà réalisé la fonctionnalité en
     * conflit sur ce <em>même objet</em> (§2.2 ; ex. l'auteur d'un constat ne peut pas le valider).
     *
     * <p>S'appuie sur l'historique d'actions ({@code audit_event}, §2.3).
     *
     * @return les conflits dynamiques applicables (vide si aucun) ; {@code BLOCK} interdit l'action
     */
    List<SodViolation> dynamicConflicts(
            UUID tenantId, UUID userId, String featureCode, String objectType, String objectId);
}
