package com.continuum.iam.api;

import java.util.UUID;

/**
 * Une fonctionnalité détenue par un utilisateur, avec sa portée (plan §2.1.4, §2.1.7).
 *
 * <p>Alimente le read model {@code GET /me/permissions} (contrat {@code app/me.yaml},
 * {@code Permissions.features[].scopes}). Un même code peut apparaître sous plusieurs portées.
 *
 * @param featureCode code de fonctionnalité (ex. {@code mission.order.approve})
 * @param scopeType   {@code TENANT}, {@code UNIT}, {@code UNIT_TREE} ou {@code MISSION}
 * @param scopeId     unité ou mission de la portée ({@code null} pour {@code TENANT})
 */
public record ScopedFeature(String featureCode, String scopeType, UUID scopeId) {
}
