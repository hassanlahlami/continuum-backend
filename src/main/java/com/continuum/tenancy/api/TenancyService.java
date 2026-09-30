package com.continuum.tenancy.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès au registre des entités (tenants) de l'installation (plan §4.7, module {@code tenancy}).
 */
public interface TenancyService {

    /** Le tenant d'identifiant donné, s'il existe. */
    Optional<Tenant> find(UUID tenantId);

    /** Les tenants actifs (pour la sélection d'entité, page COM-03). */
    List<Tenant> listActive();
}
