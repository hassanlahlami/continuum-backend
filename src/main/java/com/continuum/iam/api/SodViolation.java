package com.continuum.iam.api;

/**
 * Conflit de séparation des tâches (plan §2.2) : l'utilisateur détient deux fonctionnalités qu'une
 * règle SoD interdit de cumuler.
 *
 * @param featureA première fonctionnalité de la paire (code)
 * @param featureB seconde fonctionnalité de la paire (code)
 * @param severity {@link Severity#BLOCK} (cumul interdit) ou {@link Severity#WARN} (à signaler)
 */
public record SodViolation(String featureA, String featureB, Severity severity) {

    /** Gravité d'une règle SoD (colonne {@code sod_rule.severity}). */
    public enum Severity {
        BLOCK,
        WARN
    }
}
