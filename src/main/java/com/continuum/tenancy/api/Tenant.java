package com.continuum.tenancy.api;

import java.util.UUID;

/**
 * Entité (tenant) d'une installation (plan §4.7).
 *
 * @param id     identifiant du tenant
 * @param code   code stable (ex. {@code BANQUE-DEMO})
 * @param label  libellé affichable
 * @param active si l'entité est active
 */
public record Tenant(UUID id, String code, String label, boolean active) {
}
