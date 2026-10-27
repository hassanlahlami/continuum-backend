package com.continuum.tenancy.internal;

import com.continuum.tenancy.api.EntityActionService;
import com.continuum.tenancy.api.EntityActionService.IllegalEntityTransitionException;

/** Table de transitions du cycle de vie d'une entité, commune aux deux dialectes. */
final class EntityTransitions {

    private EntityTransitions() {
    }

    /** Statut cible, ou exception si l'action est inconnue (422) / interdite depuis ce statut (409). */
    static String target(String status, String action) {
        String target = switch (action) {
            case EntityActionService.ACTION_SUSPEND -> EntityActionService.STATUS_SUSPENDED;
            case EntityActionService.ACTION_REACTIVATE -> EntityActionService.STATUS_ACTIVE;
            case EntityActionService.ACTION_MAINTENANCE -> EntityActionService.STATUS_MAINTENANCE;
            default -> throw new IllegalArgumentException("Action d'entité inconnue : " + action);
        };
        if (!EntityActionService.actionsFrom(status).contains(action)) {
            throw new IllegalEntityTransitionException(status, action);
        }
        return target;
    }

    /** {@code platform.tenant.active} : l'entité n'est inactive que suspendue. */
    static boolean isActive(String status) {
        return !EntityActionService.STATUS_SUSPENDED.equals(status);
    }

    static String auditPayload(String action, String before, String after) {
        return "{\"action\":\"" + action + "\",\"before\":\"" + before + "\",\"after\":\"" + after + "\"}";
    }
}
