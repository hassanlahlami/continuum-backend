package com.continuum.audit.api;

import java.util.List;
import java.util.UUID;

/**
 * Lecture transverse des constats visibles par l'utilisateur (contrat {@code app/findings.yaml}, §6).
 * Le périmètre est appliqué par le serveur : seuls les constats dont l'utilisateur peut lire l'unité
 * ({@code finding.read}, §2.1.6) sont renvoyés.
 */
public interface FindingDirectory {

    /** Une page de constats visibles + le total (pagination serveur). */
    record Page(List<FindingView> items, int totalItems) {
    }

    /**
     * Constats visibles de l'utilisateur, filtrés par {@code query} (référence ou titre) et paginés.
     *
     * @param query texte libre optionnel ({@code null} = pas de filtre)
     */
    Page searchVisible(UUID tenantId, UUID userId, String query, int page, int size);

    /**
     * Constats <strong>d'une mission</strong> visibles par l'utilisateur ({@code finding.read} sur
     * l'unité auditée, §2.1.6). Alimente le cockpit de la mission.
     */
    List<FindingView> listByMission(UUID tenantId, UUID userId, UUID missionId);
}
