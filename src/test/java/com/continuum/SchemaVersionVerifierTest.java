package com.continuum;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Vérifie {@link SchemaVersionVerifier} sur une base réelle : version suffisante, schéma trop ancien,
 * ligne en échec ignorée, historique absent, et contrôle désactivé. Base fournie via {@code -Dit.db.url}
 * (sinon test neutralisé). N'utilise pas le contexte Spring (JdbcTemplate isolé), comme
 * {@code FeatureCatalogVerifierTest}.
 */
class SchemaVersionVerifierTest {

    private static final String TABLE = "flyway_schema_history";
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"),
                "Base de test absente : lancer avec -Dit.db.url=… -Dit.db.user=… -Dit.db.password=…");
        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.db.user"), System.getProperty("it.db.password"));
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void freshHistory() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS public");
        jdbc.execute("DROP TABLE IF EXISTS public." + TABLE);
        jdbc.execute("CREATE TABLE public." + TABLE
                + " (installed_rank INT PRIMARY KEY, version VARCHAR(50), success BOOLEAN NOT NULL)");
    }

    private void insert(int rank, String version, boolean success) {
        jdbc.update("INSERT INTO public." + TABLE + " (installed_rank, version, success) VALUES (?, ?, ?)",
                rank, version, success);
    }

    private SchemaVersionVerifier verifier(boolean enabled) {
        return new SchemaVersionVerifier(dataSource, TABLE, "", enabled);
    }

    @Test
    void passesWhenAppliedVersionMeetsRequirement() {
        insert(1, "202609300900", true);
        insert(2, String.valueOf(SchemaVersionVerifier.REQUIRED_SCHEMA_VERSION), true);
        insert(3, null, true); // migration répétable (R__) : ignorée

        assertThatCode(() -> verifier(true).verify()).doesNotThrowAnyException();
    }

    @Test
    void failsWhenSchemaIsOlderThanRequired() {
        insert(1, "202609300900", true);
        insert(2, "202609301000", true);

        assertThatThrownBy(() -> verifier(true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SchemaVersionVerifier.EVENT_SCHEMA_OUTDATED)
                .hasMessageContaining(String.valueOf(SchemaVersionVerifier.REQUIRED_SCHEMA_VERSION));
    }

    @Test
    void ignoresFailedMigrationRows() {
        // Une tentative récente échouée (success=false) ne doit pas compter comme appliquée.
        insert(1, "202609300900", true);
        insert(2, String.valueOf(SchemaVersionVerifier.REQUIRED_SCHEMA_VERSION), false);

        assertThatThrownBy(() -> verifier(true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SchemaVersionVerifier.EVENT_SCHEMA_OUTDATED);
    }

    @Test
    void failsWhenHistoryTableMissing() {
        jdbc.execute("DROP TABLE IF EXISTS public." + TABLE);

        assertThatThrownBy(() -> verifier(true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SchemaVersionVerifier.EVENT_SCHEMA_OUTDATED);
    }

    @Test
    void skipsCheckWhenDisabled() {
        jdbc.execute("DROP TABLE IF EXISTS public." + TABLE);

        assertThatCode(() -> verifier(false).verify()).doesNotThrowAnyException();
    }
}
