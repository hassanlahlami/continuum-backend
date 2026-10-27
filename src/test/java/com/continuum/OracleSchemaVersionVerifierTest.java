package com.continuum;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Parité Oracle de {@link SchemaVersionVerifierTest} (D4) : {@link SchemaVersionVerifier} lit l'historique
 * Flyway tel que créé par Flyway sur Oracle ({@code success NUMBER(1)}), avec deux historiques (entité +
 * PLATFORM, ADR 0012). Utilise des tables dédiées au test (jamais le vrai {@code flyway_schema_history}).
 * Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleSchemaVersionVerifierTest {

    private static final String TABLE = "CT_TEST_SCHEMA_HISTORY";
    private static final String PLATFORM_TABLE = "CT_TEST_PLATFORM_HISTORY";
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getProperty("it.oracle.url"),
                System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void freshHistory() {
        dropAll();
        for (String t : new String[] {TABLE, PLATFORM_TABLE}) {
            jdbc.execute("CREATE TABLE " + t
                    + " (installed_rank NUMBER(10) PRIMARY KEY, version VARCHAR2(50), success NUMBER(1) NOT NULL)");
        }
    }

    @AfterAll
    static void cleanup() {
        if (jdbc != null) {
            dropAll();
        }
    }

    private static void dropAll() {
        for (String t : new String[] {TABLE, PLATFORM_TABLE}) {
            jdbc.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + t
                    + " PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;");
        }
    }

    private void insert(String table, int rank, String version, boolean success) {
        jdbc.update("INSERT INTO " + table + " (installed_rank, version, success) VALUES (?, ?, ?)",
                rank, version, success ? 1 : 0);
    }

    private SchemaVersionVerifier verifier(String platformTable, boolean enabled) {
        return new SchemaVersionVerifier(dataSource, TABLE, platformTable, enabled);
    }

    private static String required() {
        return String.valueOf(SchemaVersionVerifier.REQUIRED_SCHEMA_VERSION);
    }

    @Test
    void passesWhenAppliedVersionMeetsRequirement() {
        insert(TABLE, 1, "202609300900", true);
        insert(TABLE, 2, required(), true);
        insert(TABLE, 3, null, true); // migration répétable : ignorée

        assertThatCode(() -> verifier("", true).verify()).doesNotThrowAnyException();
    }

    @Test
    void failsWhenSchemaIsOlderThanRequired() {
        insert(TABLE, 1, "202609300900", true);

        assertThatThrownBy(() -> verifier("", true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SchemaVersionVerifier.EVENT_SCHEMA_OUTDATED)
                .hasMessageContaining(required());
    }

    @Test
    void ignoresFailedMigrationRows() {
        insert(TABLE, 1, "202609300900", true);
        insert(TABLE, 2, required(), false);

        assertThatThrownBy(() -> verifier("", true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SchemaVersionVerifier.EVENT_SCHEMA_OUTDATED);
    }

    @Test
    void takesMaximumAcrossEntityAndPlatformHistories() {
        insert(TABLE, 1, "202609300900", true);
        insert(PLATFORM_TABLE, 1, required(), true);

        assertThatCode(() -> verifier(PLATFORM_TABLE, true).verify()).doesNotThrowAnyException();
    }

    @Test
    void failsWhenHistoryTableMissing() {
        jdbc.execute("DROP TABLE " + TABLE + " PURGE");

        assertThatThrownBy(() -> verifier("", true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SchemaVersionVerifier.EVENT_SCHEMA_OUTDATED);
    }

    @Test
    void skipsCheckWhenDisabled() {
        jdbc.execute("DROP TABLE " + TABLE + " PURGE");

        assertThatCode(() -> verifier("", false).verify()).doesNotThrowAnyException();
    }
}
