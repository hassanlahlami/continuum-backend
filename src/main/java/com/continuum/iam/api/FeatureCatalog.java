package com.continuum.iam.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Vues en lecture sur le registre {@link Feature} (plan §2.1.7).
 *
 * <p>API publique du module {@code iam} : les autres modules et la couche de synchronisation vers la
 * table {@code feature} passent par ici, jamais par {@code Feature.values()} en dur.
 */
public final class FeatureCatalog {

    private static final Map<String, Feature> BY_CODE;

    static {
        Map<String, Feature> byCode = new LinkedHashMap<>();
        for (Feature f : Feature.values()) {
            Feature previous = byCode.put(f.code(), f);
            if (previous != null) {
                throw new IllegalStateException("Code de fonctionnalité dupliqué : " + f.code());
            }
        }
        BY_CODE = Collections.unmodifiableMap(byCode);
    }

    private FeatureCatalog() {
    }

    /** Toutes les fonctionnalités, dans l'ordre de déclaration. */
    public static List<Feature> all() {
        return List.of(Feature.values());
    }

    /** Recherche par code stable (ex. {@code "mission.order.approve"}). */
    public static Optional<Feature> fromCode(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /** Les fonctionnalités d'un domaine (pour l'activation par module d'un tenant). */
    public static List<Feature> ofModule(FeatureModule module) {
        return Stream.of(Feature.values()).filter(f -> f.module() == module).toList();
    }

    /** Les fonctionnalités sensibles (double validation + step-up MFA, §2.1.8). */
    public static List<Feature> sensitive() {
        return Stream.of(Feature.values()).filter(Feature::sensitive).toList();
    }
}
