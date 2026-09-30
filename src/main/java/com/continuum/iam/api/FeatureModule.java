package com.continuum.iam.api;

/**
 * Domaine fonctionnel d'une {@link Feature} (plan §2.1.6, colonne {@code feature.module}).
 *
 * <p>Sert à l'<strong>activation par module</strong> pour un tenant (table {@code tenant_module}) :
 * une fonctionnalité n'est effective que si son module est activé (§2.1.4, point 3).
 *
 * <p>Ces valeurs reprennent la colonne « Module » du catalogue source de vérité
 * (`Instructions/docs/pages/FONCTIONNALITES.md`). Ce sont des domaines fonctionnels, distincts
 * des modules Spring Modulith (§4.3.1) qui structurent le code.
 */
public enum FeatureModule {
    ADMIN,
    AUDITEE,
    CCM,
    CHARTER,
    EXTERNAL,
    FINDING,
    FOLLOWUP,
    MISSION,
    OPS,
    PLAN,
    QUALITY,
    QUESTIONNAIRE,
    RECOMMENDATION,
    REPORT,
    REPORTING,
    REQUEST,
    RESOURCE,
    RISK,
    SETTINGS,
    SIGNATURE,
    TIMESHEET,
    UNIVERSE,
    VERIFICATION,
    WORKPAPER
}
