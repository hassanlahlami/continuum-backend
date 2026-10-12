package com.continuum.ccm.api;

import java.util.UUID;

/**
 * Contrôle continu (CCM, §5) de séparation des tâches : détecte les <strong>cumuls SoD interdits</strong>
 * (un même utilisateur détenant les deux fonctionnalités d'une règle {@code STATIC}/{@code BLOCK}) et
 * matérialise chaque cumul en <strong>exception</strong> (table {@code ccm_exception}, D34). Cœur du
 * différenciateur Continuous Control Monitoring — un contrôle qui tourne en continu et produit des
 * exceptions à trier.
 *
 * <p><strong>Audit par exception (D34, §5.3)</strong> : aucun constat n'est créé automatiquement ; une
 * exception retenue par un auditeur habilité devient un constat candidat rattaché à une mission.
 */
public interface SodMonitoring {

    /**
     * Exécute le contrôle pour un tenant : crée une exception par cumul détecté, de façon
     * <strong>idempotente</strong> (dé-doublonnage par référence stable).
     *
     * @return le nombre de <em>nouvelles</em> exceptions créées
     */
    int detectCumuls(UUID tenantId);
}
