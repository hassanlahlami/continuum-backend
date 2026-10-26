package com.continuum.iam.api;

import java.util.List;
import java.util.UUID;

/**
 * Détection tenant-large des cumuls interdits existants (plan §2.2), pour l'écran d'administration
 * (contrat {@code admin/tenancy-iam.yaml}, opération {@code listSodConflicts}). Contrairement à
 * {@link SegregationOfDutiesService#staticConflicts}, qui juge un utilisateur sur une unité donnée,
 * ce read model parcourt toutes les affectations valides du tenant.
 */
public interface SodConflictDirectory {

    /**
     * Conflits statiques actuels du tenant, triés de façon déterministe (règle puis utilisateur).
     *
     * @return la liste des conflits (vide si aucun)
     */
    List<SodConflictView> listStaticConflicts(UUID tenantId);
}
