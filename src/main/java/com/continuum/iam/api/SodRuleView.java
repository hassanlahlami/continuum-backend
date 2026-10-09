package com.continuum.iam.api;

import java.util.UUID;

/**
 * Règle de séparation des tâches (plan §2.2), pour la consultation côté Admin Système
 * (ligne de {@code sod_rule}). Les champs {@code type} et {@code severity} sont les valeurs brutes
 * de la base ({@code STATIC}/{@code DYNAMIC}, {@code BLOCK}/{@code WARN}) ; leur transposition vers
 * le contrat est à la charge de la couche web.
 *
 * @param id       identifiant de la règle
 * @param featureA première fonctionnalité de la paire (code)
 * @param featureB seconde fonctionnalité de la paire (code)
 * @param type     {@code STATIC} (cumul interdit) ou {@code DYNAMIC} (même objet)
 * @param severity {@code BLOCK} (interdit) ou {@code WARN} (à signaler)
 */
public record SodRuleView(
        UUID id,
        String featureA,
        String featureB,
        String type,
        String severity) {
}
