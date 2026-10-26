package com.continuum.iam.api;

import java.util.UUID;

/**
 * Décision « ce compte peut-il ouvrir une session ? » consommée par le SPI Keycloak via l'API interne
 * (plan §4.5.2, contrat {@code internal/keycloak.yaml}).
 */
public interface AccountLoginStatusService {

    /** Raison machine du refus (jamais un libellé). */
    enum Reason { NO_ACTIVE_ASSIGNMENT, TENANT_SUSPENDED, UNKNOWN_ACCOUNT }

    /** Décision ; {@code reason} est {@code null} quand {@code allowed}. */
    record Decision(boolean allowed, Reason reason) {
        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision deny(Reason reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * Tenant inconnu → {@code UNKNOWN_ACCOUNT} ; tenant suspendu → {@code TENANT_SUSPENDED} ; aucune
     * affectation active (dates valides) → {@code NO_ACTIVE_ASSIGNMENT}. {@code ACCOUNT_DISABLED} n'est
     * pas produit ici : l'état du compte est porté par Keycloak lui-même.
     */
    Decision loginStatus(UUID tenantId, UUID userId);
}
