package com.continuum.ccm.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmControlCatalog.ControlDescriptor;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Vérifie le catalogue des contrôles CCM du pack (§5) : les 3 contrôles du MVP, des catégories et types
 * de paramètres conformes aux énumérations du contrat, et des identifiants stables et distincts. Test pur
 * (pas de base ni de contexte Spring) — il verrouille la correspondance catalogue → contrat.
 */
class CcmControlCatalogTest {

    private static final Set<String> VALID_CATEGORIES =
            Set.of("CONFIGURATION", "MASTER_DATA", "TRANSACTIONS", "ACCESS", "RECONCILIATION");
    private static final Set<String> VALID_PARAM_TYPES =
            Set.of("MONEY", "NUMBER", "BOOLEAN", "STRING", "DURATION");

    @Test
    void listsTheThreeMvpControlsOfThePack() {
        assertThat(CcmControlCatalog.all()).extracting(ControlDescriptor::code)
                .containsExactlyInAnyOrder("SOD-VIREMENTS", "SEUIL-HORAIRE", "COMPTES-DORMANTS");
    }

    @Test
    void everyControlHasContractConformMetadata() {
        for (ControlDescriptor c : CcmControlCatalog.all()) {
            assertThat(c.category()).as("catégorie de %s", c.code()).isIn(VALID_CATEGORIES);
            assertThat(c.label()).isNotBlank();
            assertThat(c.normativeReference()).isNotBlank();
            assertThat(c.dataset()).isNotBlank();
            assertThat(c.parameterDefinitions()).as("paramètres de %s", c.code()).isNotEmpty();
            c.parameterDefinitions().forEach(p -> {
                assertThat(p.name()).isNotBlank();
                assertThat(p.type()).as("type du paramètre %s", p.name()).isIn(VALID_PARAM_TYPES);
            });
        }
    }

    @Test
    void idsAreStableAndDistinctPerCode() {
        assertThat(CcmControlCatalog.idOf("SEUIL-HORAIRE")).isEqualTo(CcmControlCatalog.idOf("SEUIL-HORAIRE"));
        assertThat(CcmControlCatalog.all()).extracting(ControlDescriptor::id).doesNotHaveDuplicates();
        UUID dormant = CcmControlCatalog.idOf("COMPTES-DORMANTS");
        assertThat(CcmControlCatalog.byId(dormant)).get().extracting(ControlDescriptor::code)
                .isEqualTo("COMPTES-DORMANTS");
    }
}
