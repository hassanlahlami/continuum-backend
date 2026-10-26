package com.continuum.iam.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rôle du tenant, pour la consultation côté Admin Système (plan §2.1.1).
 *
 * @param label          libellé i18n par langue ({@code fr}, {@code ar}, {@code en}…)
 * @param kind           {@code ORGANISATIONAL} ou {@code MISSION}
 * @param fromPack       rôle livré par le pack (non créé par l'administrateur)
 * @param featureCodes   codes des fonctionnalités accordées, triés
 * @param positionsCount nombre de positions qui portent ce rôle
 */
public record RoleView(
        UUID id,
        String code,
        Map<String, String> label,
        String kind,
        boolean fromPack,
        List<String> featureCodes,
        int positionsCount) {
}
