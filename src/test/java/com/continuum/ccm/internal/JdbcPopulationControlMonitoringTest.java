package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmPopulationGateway;
import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.MovementAnomaly;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie la matérialisation des anomalies CBS en <strong>exceptions</strong> ({@code ccm_exception}, D34),
 * §5, sur base réelle : le connecteur CBS est <strong>bouchonné</strong> (déjà testé sur Oracle par
 * {@link OracleCcmPopulationGatewayTest}) et on vérifie l'écriture côté plateforme — références stables,
 * criticité, statut {@code NEW} (à trier), chaîne de preuve et <strong>idempotence</strong>. Base via
 * {@code -Dit.db.url}.
 */
class JdbcPopulationControlMonitoringTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private static DataSource dataSource;
    private static JdbcTemplate jt;

    @BeforeAll
    static void applySchema() {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"),
                "Base de test absente : lancer avec -Dit.db.url=… -Dit.db.user=… -Dit.db.password=…");
        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(pg), "Scripts du continuum-migrator introuvables : " + pg);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.db.user"), System.getProperty("it.db.password"));
        jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");

        run(pg.resolve("V202609300900__platform_feature_catalog.sql"));
        run(pg.resolve("R__feature_catalog.sql"));
        run(pg.resolve("V202609301000__entity_iam_structure.sql"));
        run(pg.resolve("V202610091200__entity_mission.sql"));
        run(pg.resolve("V202610091300__entity_finding.sql"));
        run(pg.resolve("V202610091500__finding_frap.sql"));
        run(pg.resolve("V202610121000__ccm_exception.sql"));
        run(pg.resolve("V202610141000__ccm_run.sql"));
        run(pg.resolve("V202611011000__ccm_exception_run_mission.sql"));
    }

    @BeforeEach
    void clearExceptions() {
        jt.update("DELETE FROM ccm_exception");
    }

    private static void run(Path script) {
        try (Connection c = dataSource.getConnection()) {
            Resource r = new FileSystemResource(script);
            ScriptUtils.executeSqlScript(c, new EncodedResource(r, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    /** Connecteur CBS bouchonné : renvoie des anomalies fixes quels que soient les critères. */
    private static JdbcPopulationControlMonitoring monitoring() {
        CcmPopulationGateway stub = new CcmPopulationGateway() {
            @Override
            public List<MovementAnomaly> dormantAccountMovements(DormantAccountCriteria c) {
                return List.of(new MovementAnomaly(5L, "A002000000003", LocalDate.of(2026, 9, 5),
                        LocalDate.of(2026, 9, 5), new BigDecimal("45000.00"), "D", "VIR", "U003", "U004", "BATCH",
                        "DORMANT"));
            }

            @Override
            public List<MovementAnomaly> outOfBoundsMovements(OutOfBoundsCriteria c) {
                return List.of(new MovementAnomaly(4L, "A001000000002", LocalDate.of(2026, 9, 12),
                        LocalDate.of(2026, 9, 14), new BigDecimal("2500000.00"), "D", "VIR", "U001", "U002", "GUICHET",
                        "AMOUNT"));
            }
        };
        return new JdbcPopulationControlMonitoring(stub, dataSource, "amplitude-test (JDBC)");
    }

    @Test
    void dormantControlCreatesException() {
        int created = monitoring().recordDormantExceptions(TENANT, "COMPTES-DORMANTS",
                new DormantAccountCriteria(12, new BigDecimal("10000"), List.of(), FROM, TO),
                new BigDecimal("100000"));
        assertThat(created).isEqualTo(1);

        // D34 : écrit une exception à trier, PAS un constat.
        assertThat(jt.queryForObject("SELECT count(*) FROM finding WHERE reference = 'CCM-DORM-5'", Integer.class))
                .isZero();
        assertThat(jt.queryForObject("SELECT status_code FROM ccm_exception WHERE reference = 'CCM-DORM-5'",
                String.class)).isEqualTo("NEW");
        assertThat(jt.queryForObject("SELECT control_code FROM ccm_exception WHERE reference = 'CCM-DORM-5'",
                String.class)).isEqualTo("COMPTES-DORMANTS");
        // Chaîne de preuve : l'enregistrement source du CBS est porté par l'exception (§5.3).
        assertThat(jt.queryForObject("SELECT source_record->>'accountId' FROM ccm_exception "
                + "WHERE reference = 'CCM-DORM-5'", String.class)).isEqualTo("A002000000003");
        // 45 000 < seuil de sortie importante (100 000) → ÉLEVÉE, pas CRITIQUE.
        assertThat(jt.queryForObject("SELECT criticality_code FROM ccm_exception WHERE reference = 'CCM-DORM-5'",
                String.class)).isEqualTo("HIGH");
    }

    @Test
    void dormantControlEscalatesLargeOutflow() {
        int created = monitoring().recordDormantExceptions(TENANT, "COMPTES-DORMANTS",
                new DormantAccountCriteria(12, new BigDecimal("10000"), List.of(), FROM, TO),
                new BigDecimal("10000"));
        assertThat(created).isEqualTo(1);
        assertThat(jt.queryForObject("SELECT criticality_code FROM ccm_exception WHERE reference = 'CCM-DORM-5'",
                String.class)).isEqualTo("CRITICAL");
    }

    @Test
    void outOfBoundsControlCreatesException() {
        int created = monitoring().recordOutOfBoundsExceptions(TENANT, "SEUIL-HORAIRE",
                new OutOfBoundsCriteria(new BigDecimal("1000000"), List.of("SAT", "SUN"), 2, List.of("BATCH"), FROM, TO));
        assertThat(created).isEqualTo(1);
        assertThat(jt.queryForObject("SELECT criticality_code FROM ccm_exception WHERE reference = 'CCM-OOB-4'",
                String.class)).isEqualTo("HIGH");
        // Paramètres d'exécution conservés pour la rejouabilité (§5.3).
        assertThat(jt.queryForObject("SELECT parameters_used->>'thresholdAmount' FROM ccm_exception "
                + "WHERE reference = 'CCM-OOB-4'", String.class)).isEqualTo("1000000");
    }

    @Test
    void controlsAreIdempotent() {
        var crit = new DormantAccountCriteria(12, new BigDecimal("10000"), List.of(), FROM, TO);
        assertThat(monitoring().recordDormantExceptions(TENANT, "COMPTES-DORMANTS", crit, new BigDecimal("100000")))
                .isEqualTo(1);
        // Deuxième exécution : aucune nouvelle ligne (dé-doublonnage par référence).
        assertThat(monitoring().recordDormantExceptions(TENANT, "COMPTES-DORMANTS", crit, new BigDecimal("100000")))
                .isEqualTo(0);
        assertThat(jt.queryForObject("SELECT count(*) FROM ccm_exception WHERE reference = 'CCM-DORM-5'",
                Integer.class)).isEqualTo(1);
    }
}
