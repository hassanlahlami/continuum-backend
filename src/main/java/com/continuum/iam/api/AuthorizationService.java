package com.continuum.iam.api;

import java.util.UUID;

/**
 * Moteur d'autorisation (plan §2.1.4, §2.1.7) : le code ne connaît que des fonctionnalités.
 *
 * <p>{@code can(...)} est vrai si <strong>au moins une</strong> source accorde la fonctionnalité
 * (affectation active dont la position porte un rôle la contenant, avec une portée couvrant l'unité)
 * <strong>et</strong> qu'aucune règle SoD ne la bloque, le module de la fonctionnalité étant activé
 * pour le tenant.
 */
public interface AuthorizationService {

    /**
     * L'utilisateur détient-il {@code featureCode} sur l'unité {@code unitId} ?
     *
     * <p>Couvre les droits issus des <em>affectations</em> (portées {@code TENANT} / {@code UNIT} /
     * {@code UNIT_TREE}) et les <em>restrictions de délégation partielle</em>. Les droits issus des
     * rôles de mission (portée mission) relèvent d'une surcharge dédiée (à venir).
     *
     * @param tenantId    entité (tenant) courante
     * @param userId      utilisateur évalué
     * @param featureCode code de fonctionnalité (ex. {@code "mission.order.approve"})
     * @param unitId      unité organisationnelle de la ressource
     */
    boolean can(UUID tenantId, UUID userId, String featureCode, UUID unitId);

    /**
     * L'utilisateur détient-il {@code featureCode} sur la mission {@code missionId} via un
     * <em>rôle de mission</em> (§2.1.1, second volet de la requête §2.1.6) ?
     *
     * <p>Complète {@link #can(UUID, UUID, String, UUID)} : les droits d'une ressource de mission sont
     * l'union des droits d'unité (affectations) et des droits contextuels de la mission.
     *
     * @param tenantId    entité (tenant) courante
     * @param userId      utilisateur évalué
     * @param featureCode code de fonctionnalité
     * @param missionId   mission de la ressource
     */
    boolean canOnMission(UUID tenantId, UUID userId, String featureCode, UUID missionId);
}
