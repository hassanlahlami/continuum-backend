package com.continuum;

/**
 * Profil de déploiement « surface » (plan §4.3.2/§4.3.3) : un même jar, déployé plusieurs fois avec
 * des rôles différents dictés par l'environnement (property {@code continuum.surface}, variable
 * {@code CONTINUUM_SURFACE}) :
 *
 * <ul>
 *   <li>{@code app} — n'expose que l'API Fonctionnelle ({@code /api/app/**}) ;</li>
 *   <li>{@code admin} — n'expose que l'API Admin Système ({@code /api/admin/**}) ;</li>
 *   <li>{@code worker} — aucune API métier : porte la <strong>planification continue</strong> (moteur
 *       CCM), pour qu'un seul type d'instance exécute les travaux de fond (pas de doublons quand l'API
 *       est répliquée) ;</li>
 *   <li>{@code all} (défaut) — tout-en-un : toutes les surfaces sur une seule instance (dev, mono-nœud).</li>
 * </ul>
 *
 * <p>Les constantes {@code ON_*} sont des expressions SpEL destinées à {@code @ConditionalOnExpression}
 * (voir {@link SecurityConfig} et le moteur CCM). Chaque composant est actif sur <em>sa</em> surface
 * <strong>ou</strong> en tout-en-un ({@code all}), jamais sur une surface étrangère.
 */
public final class DeploymentSurface {

    /** Property d'environnement portant la surface (défaut {@code all} si absente). */
    public static final String PROPERTY = "continuum.surface";

    private static final String VALUE = "'${continuum.surface:all}'";

    /** API Fonctionnelle active : surface {@code app} ou {@code all}. */
    public static final String ON_APP = VALUE + " == 'all' or " + VALUE + " == 'app'";

    /** API Admin Système active : surface {@code admin} ou {@code all}. */
    public static final String ON_ADMIN = VALUE + " == 'all' or " + VALUE + " == 'admin'";

    /** Planification continue (CCM) active : surface {@code worker} ou {@code all}. */
    public static final String ON_WORKER = VALUE + " == 'all' or " + VALUE + " == 'worker'";

    private DeploymentSurface() {
    }
}
