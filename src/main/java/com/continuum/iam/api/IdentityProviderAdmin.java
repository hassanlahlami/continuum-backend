package com.continuum.iam.api;

import java.util.UUID;

/** Opérations d'administration déléguées au fournisseur d'identité (Keycloak, §4.5) pour un compte Continuum. */
public interface IdentityProviderAdmin {

    /** Supprime les facteurs MFA enregistrés (OTP / WebAuthn) : ils seront re-enrôlés à la prochaine connexion. */
    void resetMfa(UUID continuumUserId);

    /** Ferme toutes les sessions actives du compte. */
    void closeSessions(UUID continuumUserId);

    class IdentityProviderUnavailableException extends RuntimeException {
        public IdentityProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
