package com.continuum.iam.api;

import java.util.List;
import java.util.UUID;

/**
 * Consultation des règles de séparation des tâches d'un tenant (plan §2.2), pour l'écran
 * d'administration (contrat {@code admin/tenancy-iam.yaml}, opération {@code listSodRules}).
 *
 * <p>Read model en lecture seule : liste les lignes de {@code sod_rule} (statiques et dynamiques),
 * sans calcul de conflit (voir {@link SegregationOfDutiesService} pour la détection effective).
 */
public interface SodRuleDirectory {

    /**
     * Règles SoD déclarées pour le tenant, triées de façon déterministe.
     *
     * @return la liste des règles (vide si aucune)
     */
    List<SodRuleView> listRules(UUID tenantId);
}
