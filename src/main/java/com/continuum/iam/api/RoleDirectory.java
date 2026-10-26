package com.continuum.iam.api;

import java.util.List;
import java.util.UUID;

/**
 * Consultation des rôles d'un tenant avec leurs fonctionnalités (plan §2.1.1), pour les écrans
 * d'administration (contrat {@code admin/tenancy-iam.yaml}, {@code listRoles} et
 * {@code getRoleFeatureMatrix}). Read model en lecture seule ; l'écriture passe par
 * {@link RoleService}.
 */
public interface RoleDirectory {

    /** Rôles du tenant, triés par code (liste vide si aucun). */
    List<RoleView> listRoles(UUID tenantId);
}
