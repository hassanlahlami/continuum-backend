package com.continuum.iam.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Actions d'administration sur un compte utilisateur (contrat {@code admin/tenancy-iam.yaml},
 * {@code performUserAction}, D40) : verrouiller, déverrouiller, désactiver (état porté par
 * {@code platform.user_account}, verrou optimiste), réinitialiser la MFA et fermer les sessions (déléguées au
 * fournisseur d'identité, {@link IdentityProviderAdmin}). Le droit {@code admin.user.manage} est contrôlé à la
 * frontière HTTP ; chaque action est journalisée (§2.3).
 */
public interface UserAccountActionService {

    String ACTION_LOCK = "lock";
    String ACTION_UNLOCK = "unlock";
    String ACTION_RESET_MFA = "reset-mfa";
    String ACTION_DISABLE = "disable";
    String ACTION_CLOSE_SESSIONS = "close-sessions";

    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_LOCKED = "LOCKED";
    String STATUS_DISABLED = "DISABLED";
    String STATUS_NO_ASSIGNMENT = "NO_ASSIGNMENT";

    /** Actions qui exigent un commentaire (motif). */
    static boolean requiresComment(String action) {
        return ACTION_LOCK.equals(action) || ACTION_DISABLE.equals(action);
    }

    /** Codes des actions permises sur ce compte (indépendamment du droit, calculé par l'appelant). */
    static List<String> actionsFor(String status, boolean mfaConfigured, boolean self) {
        if (STATUS_DISABLED.equals(status)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (!self) {
            out.add(STATUS_LOCKED.equals(status) ? ACTION_UNLOCK : ACTION_LOCK);
            if (mfaConfigured) {
                out.add(ACTION_RESET_MFA);
            }
            out.add(ACTION_DISABLE);
        }
        out.add(ACTION_CLOSE_SESSIONS);
        return out;
    }

    /**
     * @throws UnknownUserException       compte inconnu (→ 404)
     * @throws IllegalArgumentException   code d'action inconnu ou commentaire manquant (→ 422)
     * @throws IllegalUserActionException action interdite dans l'état courant ou sur son propre compte (→ 409)
     * @throws StaleUserException         version obsolète (→ 409)
     * @throws IdentityProviderAdmin.IdentityProviderUnavailableException fournisseur d'identité injoignable (→ 503)
     */
    UserAccountView perform(UserActionCommand command);

    /** @param expectedVersion version attendue ({@code null} = pas de contrôle) */
    record UserActionCommand(UUID actorTenantId, UUID actorUserId, UUID userId, String actionCode,
                             Long expectedVersion, String comment) {
    }

    record UserAccountView(UUID id, UUID tenantId, String username, String source, String status,
                           boolean mfaConfigured, Instant lastLogin, long version) {
    }

    class UnknownUserException extends RuntimeException {
        public UnknownUserException(UUID id) {
            super("Utilisateur inconnu : " + id);
        }
    }

    class IllegalUserActionException extends RuntimeException {
        public IllegalUserActionException(String status, String action) {
            super("Action « " + action + " » impossible sur ce compte (statut " + status + ")");
        }
    }

    class StaleUserException extends RuntimeException {
        public StaleUserException(long expected, long actual) {
            super("Version obsolète : attendue " + expected + ", actuelle " + actual);
        }
    }
}
