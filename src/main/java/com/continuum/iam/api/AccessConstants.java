package com.continuum.iam.api;

/**
 * Valeurs d'accès spéciales, <strong>hors catalogue de fonctionnalités</strong>
 * (`Instructions/docs/pages/FONCTIONNALITES.md`, en-tête).
 *
 * <p>Utilisées par {@code x-required-feature} dans les contrats OpenAPI quand l'accès ne dépend pas
 * d'une {@link Feature} précise.
 */
public final class AccessConstants {

    /** API publique : aucun jeton requis (ex. {@code GET /api/public/theme}, §1.6.3). */
    public static final String PUBLIC = "public";

    /** Tout utilisateur authentifié, sans fonctionnalité particulière (ex. {@code /me}). */
    public static final String AUTHENTICATED = "authenticated";

    private AccessConstants() {
    }
}
