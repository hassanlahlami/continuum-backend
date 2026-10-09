package com.continuum.audit.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lecture des missions visibles par l'utilisateur (contrat {@code app/missions.yaml}, §6). Le serveur
 * décide du périmètre : seules les missions dont l'utilisateur peut lire l'unité ({@code mission.read},
 * §2.1.6) sont renvoyées.
 */
public interface MissionDirectory {

    /** Une page de missions visibles + le total (pour la pagination serveur). */
    record Page(List<MissionView> items, int totalItems) {
    }

    /**
     * Missions visibles de l'utilisateur, filtrées par {@code query} (référence ou titre) et paginées.
     *
     * @param query texte libre optionnel ({@code null} = pas de filtre)
     */
    Page listVisible(UUID tenantId, UUID userId, String query, int page, int size);

    /** La mission si elle existe <strong>et</strong> est visible par l'utilisateur. */
    Optional<MissionView> findVisible(UUID tenantId, UUID userId, UUID missionId);
}
