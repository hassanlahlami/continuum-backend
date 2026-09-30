package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.iam.api.AuthorizationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie {@link JdbcAuthorizationService} de bout en bout (plan §2.1.6) : schéma réel appliqué par
 * les scripts du {@code continuum-migrator}, jeu de données semé, puis évaluation de la portée.
 *
 * <p><strong>Base fournie</strong> via {@code -Dit.db.url} (+ {@code it.db.user}, {@code it.db.password}) :
 * un PostgreSQL de test (service CI, ou conteneur lancé à côté). À défaut, le test se neutralise
 * ({@link Assumptions}). Cible plan : Testcontainers (§8.4) — non utilisé ici, la négociation
 * docker-java échoue contre Docker Engine 29 sur ce poste (problème d'environnement, pas de code).
 *
 * <p>Le schéma vient d'un <strong>autre dépôt</strong> (multi-repo, D38) : scripts lus par chemin
 * relatif ; en CI ils devront être fournis (sous-module git / artefact).
 */
class JdbcAuthorizationServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID DIV_FIN = UUID.fromString("b0000000-0000-0000-0000-000000000003");
    private static final UUID DIV_IT = UUID.fromString("b0000000-0000-0000-0000-000000000004");
    private static final UUID MISSION = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MISSION = UUID.fromString("f0000000-0000-0000-0000-000000000002");

    private static DataSource dataSource;

    @BeforeAll
    static void applySchemaAndSeed() {
        String url = prop("it.db.url");
        Assumptions.assumeTrue(isSet(url),
                "Base de test absente : lancer avec -Dit.db.url=jdbc:postgresql://host:port/db "
                        + "-Dit.db.user=… -Dit.db.password=…");

        Path migrator = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(migrator),
                "Scripts du continuum-migrator introuvables (multi-repo) : " + migrator);

        dataSource = new DriverManagerDataSource(url, prop("it.db.user"), prop("it.db.password"));

        run(new FileSystemResource(migrator.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(migrator.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(migrator.resolve("V202609301000__entity_iam_structure.sql")));
        run(new ClassPathResource("iam-authz-seed.sql"));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Échec d'application du script " + script, e);
        }
    }

    private static AuthorizationService service() {
        return new JdbcAuthorizationService(dataSource);
    }

    private static String prop(String key) {
        return System.getProperty(key);
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank() && !v.startsWith("${");
    }

    @Test
    void grantsOnDescendantUnit_viaUnitTreeScope() {
        assertThat(service().can(TENANT, USER, "mission.order.approve", DIV_FIN)).isTrue();
    }

    @Test
    void deniesOutsideSubtree() {
        assertThat(service().can(TENANT, USER, "mission.order.approve", DIV_IT)).isFalse();
    }

    @Test
    void deniesFeatureNotGrantedByAnyRole() {
        assertThat(service().can(TENANT, USER, "finding.validate", DIV_FIN)).isFalse();
    }

    @Test
    void grantsOnMission_viaMissionRole() {
        assertThat(service().canOnMission(TENANT, USER, "finding.write", MISSION)).isTrue();
    }

    @Test
    void deniesOnMissionWhereUserIsNotMember() {
        assertThat(service().canOnMission(TENANT, USER, "finding.write", OTHER_MISSION)).isFalse();
    }

    @Test
    void deniesMissionFeatureNotGrantedByMissionRole() {
        assertThat(service().canOnMission(TENANT, USER, "mission.order.approve", MISSION)).isFalse();
    }
}
