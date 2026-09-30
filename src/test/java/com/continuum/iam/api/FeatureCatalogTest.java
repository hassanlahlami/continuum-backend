package com.continuum.iam.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verrouille le registre de fonctionnalités (plan §2.1.7) contre le catalogue source de vérité
 * `Instructions/docs/pages/FONCTIONNALITES.md` : 119 fonctionnalités, dont 35 sensibles.
 */
class FeatureCatalogTest {

    /** Nombre de fonctionnalités au catalogue (FONCTIONNALITES.md, 2026-09-22). */
    private static final int EXPECTED_TOTAL = 119;

    /** Nombre de fonctionnalités marquées sensibles (JOURNAL 2026-09-22 : 35). */
    private static final int EXPECTED_SENSITIVE = 35;

    /** Convention de code : segments en minuscules séparés par des points, tiret autorisé. */
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z0-9-]+)+$");

    @Test
    void hasExpectedFeatureCount() {
        assertThat(FeatureCatalog.all()).hasSize(EXPECTED_TOTAL);
    }

    @Test
    void hasExpectedSensitiveCount() {
        assertThat(FeatureCatalog.sensitive()).hasSize(EXPECTED_SENSITIVE);
    }

    @Test
    void allCodesAreUniqueAndWellFormed() {
        List<String> codes = FeatureCatalog.all().stream().map(Feature::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).allSatisfy(code -> assertThat(CODE.matcher(code).matches())
                .as("code mal formé : %s", code)
                .isTrue());
    }

    @Test
    void enumNameMatchesCode() {
        for (Feature f : Feature.values()) {
            String expectedName = f.code().toUpperCase().replace('.', '_').replace('-', '_');
            assertThat(f.name())
                    .as("nom d'enum incohérent avec le code %s", f.code())
                    .isEqualTo(expectedName);
        }
    }

    @Test
    void fromCodeRoundTrips() {
        for (Feature f : Feature.values()) {
            assertThat(FeatureCatalog.fromCode(f.code())).contains(f);
        }
        assertThat(FeatureCatalog.fromCode("does.not.exist")).isEmpty();
    }

    @Test
    void everyFeatureHasAModuleAndLabelKey() {
        for (Feature f : Feature.values()) {
            assertThat(f.module()).as("module manquant pour %s", f.code()).isNotNull();
            assertThat(f.labelKey()).isEqualTo("feature." + f.code());
        }
    }

    @Test
    void noneDeprecatedYet() {
        assertThat(FeatureCatalog.all()).noneMatch(Feature::deprecated);
    }
}
