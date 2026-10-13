package com.continuum.ccm.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paramétrage des contrôles CCM par tenant (plan §5, CCM-03). Fournit, pour un tenant, les contrôles
 * <strong>activés</strong> et leurs paramètres (seuils, listes, codes) — valeurs livrées par le pack,
 * modifiables par écran, jamais codées en dur (§garde-fous). Le moteur lit ici ses paramètres.
 */
public interface CcmControlDirectory {

    /** Un contrôle paramétré : son code, son activation et ses paramètres propres. */
    record CcmControl(String code, boolean enabled, Map<String, Object> params) {

        /** Paramètre chaîne, ou {@code defaultValue} si absent. */
        public String string(String key, String defaultValue) {
            Object v = params.get(key);
            return v == null ? defaultValue : v.toString();
        }

        /** Paramètre entier, ou {@code defaultValue} si absent / non numérique. */
        public int integer(String key, int defaultValue) {
            Object v = params.get(key);
            return v instanceof Number n ? n.intValue() : defaultValue;
        }

        /** Paramètre montant, ou {@code defaultValue} si absent / non numérique. */
        public java.math.BigDecimal amount(String key, java.math.BigDecimal defaultValue) {
            Object v = params.get(key);
            if (v instanceof Number n) {
                return new java.math.BigDecimal(n.toString());
            }
            return defaultValue;
        }

        /** Paramètre liste de chaînes, ou liste vide si absent. */
        public List<String> strings(String key) {
            Object v = params.get(key);
            if (v instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            return List.of();
        }
    }

    /** Les contrôles activés du tenant, avec leurs paramètres. */
    List<CcmControl> activeControls(UUID tenantId);

    /**
     * Tous les contrôles paramétrés du tenant (activés ou non), avec leur drapeau d'activation et leurs
     * paramètres — base de la bibliothèque de contrôles ({@code listCcmControls}, cockpit CCM). Les
     * contrôles jamais configurés pour ce tenant n'apparaissent pas ici (le catalogue du pack les complète).
     */
    List<CcmControl> allControls(UUID tenantId);
}
