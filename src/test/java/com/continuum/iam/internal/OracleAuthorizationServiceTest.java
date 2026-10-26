package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.continuum.iam.api.ScopedFeature;
import com.continuum.testsupport.OracleTestSchema;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Vérifie {@link OracleAuthorizationService} sur une <strong>vraie base Oracle</strong> (§2.1.6, D4) :
 * portée {@code UNIT_TREE} via la table de fermeture, {@code RAW(16)}, {@code NUMBER(1)}.
 *
 * <p>Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:thin:@…} est fourni ; schéma à
 * deux comptes PLATFORM / entité via {@link com.continuum.testsupport.OracleTestSchema} (ADR 0012).
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleAuthorizationServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID DIR_AUDIT = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final UUID DIV_FIN = UUID.fromString("b0000000-0000-0000-0000-000000000003");
    private static final UUID DIV_IT = UUID.fromString("b0000000-0000-0000-0000-000000000004");
    private static final UUID MISSION = UUID.fromString("f0000000-0000-0000-0000-000000000001");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void applySchemaAndSeed() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();

        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("R__feature_catalog.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.seed(new ClassPathResource("iam-oracle-authz-seed.sql"));
    }

    private static OracleAuthorizationService service() {
        return new OracleAuthorizationService(dataSource);
    }

    @Test
    void grantsOnDescendantUnit_viaClosureTable() {
        assertThat(service().can(TENANT, USER, "mission.order.approve", DIV_FIN)).isTrue();
    }

    @Test
    void deniesOutsideSubtree() {
        assertThat(service().can(TENANT, USER, "mission.order.approve", DIV_IT)).isFalse();
    }

    @Test
    void deniesFeatureNotGranted() {
        assertThat(service().can(TENANT, USER, "finding.validate", DIV_FIN)).isFalse();
    }

    @Test
    void effectiveFeaturesOnDescendantUnit() {
        assertThat(service().effectiveFeatures(TENANT, USER, DIV_FIN)).containsExactly("mission.order.approve");
    }

    @Test
    void whoCanReturnsHolder() {
        assertThat(service().whoCan(TENANT, "mission.order.approve", DIV_FIN)).containsExactly(USER);
    }

    @Test
    void permissionsListUnitAndMissionScopes() {
        assertThat(service().permissions(TENANT, USER))
                .extracting(ScopedFeature::featureCode, ScopedFeature::scopeType, ScopedFeature::scopeId)
                .containsExactlyInAnyOrder(
                        tuple("mission.order.approve", "UNIT_TREE", DIR_AUDIT),
                        tuple("finding.write", "MISSION", MISSION));
    }
}

