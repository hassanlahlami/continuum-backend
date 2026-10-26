package com.continuum.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.MissionDirectory;
import com.continuum.testsupport.OracleTestSchema;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Vérifie les read models <strong>Oracle</strong> missions/constats (§6, D4) sur une vraie base Oracle :
 * périmètre via la table de fermeture {@code org_unit_closure} (portée {@code UNIT_TREE}), RAW(16),
 * pagination {@code OFFSET/FETCH}. Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleAuditReadModelTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void applyAndSeed() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();

        // Schéma Oracle persistant entre exécutions : on repart d'un schéma propre.
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("""
                BEGIN
                  FOR t IN (SELECT table_name FROM user_tables) LOOP
                    EXECUTE IMMEDIATE 'DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE';
                  END LOOP;
                END;
                """);

        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("R__feature_catalog.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202610091200__entity_mission.sql");
        db.entity("V202610091300__entity_finding.sql");
        db.entity("V202610091500__finding_frap.sql");
        db.seed(new ClassPathResource("audit-oracle-read-seed.sql"));
    }

    @Test
    void missionsInScopeViaClosureTable() {
        MissionDirectory directory = new OracleMissionDirectory(dataSource);

        MissionDirectory.Page page = directory.listVisible(TENANT, USER, null, 0, 25);
        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(m -> assertThat(m.reference()).isEqualTo("MIS-A001"));

        assertThat(directory.listVisible(TENANT, STRANGER, null, 0, 25).items()).isEmpty();
        assertThat(directory.listVisible(TENANT, USER, "A001", 0, 25).items()).hasSize(1);
    }

    @Test
    void findingsInScopeViaClosureTable() {
        FindingDirectory directory = new OracleFindingDirectory(dataSource);

        FindingDirectory.Page page = directory.searchVisible(TENANT, USER, null, null, null, 0, 25);
        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(f -> assertThat(f.reference()).isEqualTo("CST-A001-01"));

        assertThat(directory.searchVisible(TENANT, STRANGER, null, null, null, 0, 25).items()).isEmpty();
    }
}
