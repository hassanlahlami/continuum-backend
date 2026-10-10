package com.continuum.ccm.api;

import java.util.UUID;

/**
 * Contrôle continu (CCM, §5) de séparation des tâches : détecte les <strong>cumuls SoD interdits</strong>
 * (un même utilisateur détenant les deux fonctionnalités d'une règle {@code STATIC}/{@code BLOCK}) et
 * matérialise chaque cumul en <strong>constat</strong> (source {@code CCM}). Cœur du différenciateur
 * Continuous Control Monitoring — un contrôle qui tourne en continu et produit des constats.
 */
public interface SodMonitoring {

    /**
     * Exécute le contrôle pour un tenant : crée un constat par cumul détecté, de façon
     * <strong>idempotente</strong> (dé-doublonnage par référence stable).
     *
     * @return le nombre de <em>nouveaux</em> constats créés
     */
    int detectCumuls(UUID tenantId);
}
