package com.continuum.iam.api;

/** Triggers a user-storage (directory) synchronisation on the identity provider (Keycloak, §4.5). */
public interface DirectoryFederation {

    /**
     * Runs a full synchronisation of the user-storage component named {@code sourceCode} and waits for its end.
     *
     * @return number of users the provider reported as failed (0 when everything was imported)
     * @throws IdentityProviderAdmin.IdentityProviderUnavailableException if the provider is unreachable, not
     *                                                                    configured, or the component is unknown
     */
    int fullSync(String sourceCode);
}
