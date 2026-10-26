package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.continuum.iam.api.ScopedFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie {@link OracleAuthorizationService} sur une <strong>vraie base Oracle</strong> (§2.1.6, D4) :
 * portée {@code UNIT_TREE} via la table de fermeture, {@code RAW(16)}, {@code NUMBER(1)}.
 *
 * <p>Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:thin:@…} est fourni (schéma courant =
 * {@code PLATFORM}, où atterrissent les objets qualifiés {@code platform.*} et les tables d'entité).
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
        String url = System.getProperty("it.oracle.url");
        Path oracle = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "oracle")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(oracle), "Scripts Oracle du migrateur introuvables : " + oracle);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");

        run(new FileSystemResource(oracle.resolve("platform").resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(oracle.resolve("platform").resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202609301000__entity_iam_structure.sql")));
        run(new ClassPathResource("iam-oracle-authz-seed.sql"));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
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

