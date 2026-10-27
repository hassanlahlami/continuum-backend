package com.continuum.tenancy.api;

import java.util.List;
import java.util.UUID;

/**
 * Cycle de vie d'une entité (tenant) côté Admin Système (contrat {@code admin/tenancy-iam.yaml},
 * {@code performEntityAction}, §4.7) : <strong>suspendre</strong>, <strong>réactiver</strong>, passer en
 * <strong>maintenance</strong>. Le serveur décide la transition valide et pose un verrou optimiste ; le droit
 * {@code admin.entity.manage} est contrôlé à la frontière HTTP. Chaque changement est journalisé (§2.3).
 */
public interface EntityActionService {

    String ACTION_SUSPEND = "suspend";
    String ACTION_REACTIVATE = "reactivate";
    String ACTION_MAINTENANCE = "maintenance";

    String STATUS_PROVISIONING = "PROVISIONING";
    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_SUSPENDED = "SUSPENDED";
    String STATUS_MAINTENANCE = "MAINTENANCE";

    /** Codes des actions permises depuis ce statut (indépendamment du droit, calculé par l'appelant). */
    static List<String> actionsFrom(String status) {
        return switch (status) {
            case STATUS_ACTIVE -> List.of(ACTION_MAINTENANCE, ACTION_SUSPEND);
            case STATUS_MAINTENANCE -> List.of(ACTION_REACTIVATE, ACTION_SUSPEND);
            case STATUS_SUSPENDED -> List.of(ACTION_REACTIVATE);
            default -> List.of();
        };
    }

    /**
     * Applique l'action.
     *
     * @throws UnknownEntityException           entité inconnue (→ 404)
     * @throws IllegalArgumentException         code d'action inconnu (→ 422)
     * @throws IllegalEntityTransitionException transition interdite depuis le statut courant (→ 409)
     * @throws StaleEntityException             version obsolète (→ 409)
     */
    EntityView perform(EntityActionCommand command);

    /**
     * @param actorTenantId   tenant de l'acteur (porte la chaîne d'audit)
     * @param entityId        entité ciblée
     * @param expectedVersion version attendue ({@code null} = pas de contrôle)
     */
    record EntityActionCommand(
            UUID actorTenantId, UUID actorUserId, UUID entityId, String actionCode, Integer expectedVersion) {
    }

    /** Projection d'une entité du registre. */
    record EntityView(UUID id, String code, String label, String status, int version) {
    }

    class UnknownEntityException extends RuntimeException {
        public UnknownEntityException(UUID id) {
            super("Entité inconnue : " + id);
        }
    }

    class IllegalEntityTransitionException extends RuntimeException {
        public IllegalEntityTransitionException(String status, String action) {
            super("Action « " + action + " » impossible depuis le statut " + status);
        }
    }

    class StaleEntityException extends RuntimeException {
        public StaleEntityException(int expected, int actual) {
            super("Version obsolète : attendue " + expected + ", actuelle " + actual);
        }
    }
}
