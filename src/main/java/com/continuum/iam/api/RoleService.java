package com.continuum.iam.api;

import java.util.UUID;

/** Écriture des rôles du tenant (plan §2.1.1, §2.1.8). */
public interface RoleService {

    /**
     * Crée un rôle avec ses fonctionnalités et journalise l'opération (§2.3).
     *
     * @return l'identifiant du rôle créé
     */
    UUID create(CreateRoleCommand command);
}
