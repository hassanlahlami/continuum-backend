package com.continuum.iam.api;

import java.util.List;
import java.util.UUID;

/**
 * Demande de création d'un rôle du tenant (plan §2.1.1).
 *
 * @param tenantId     entité
 * @param actorUserId  auteur (journalisé)
 * @param code         code unique du rôle dans le tenant
 * @param label        libellé (locale fr ; stocké en JSON i18n)
 * @param kind         {@code ORGANISATIONAL} ou {@code MISSION}
 * @param featureCodes fonctionnalités portées par le rôle (codes du catalogue)
 */
public record CreateRoleCommand(
        UUID tenantId,
        UUID actorUserId,
        String code,
        String label,
        String kind,
        List<String> featureCodes) {
}
