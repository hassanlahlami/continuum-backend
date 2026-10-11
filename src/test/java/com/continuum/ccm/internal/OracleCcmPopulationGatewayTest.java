package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmPopulationGateway;
import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.MovementAnomaly;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie le connecteur CBS « Amplitude » (Oracle, §5, D3) sur une vraie base Oracle : les contrôles
 * sur population CCM-2 (hors seuil / hors heures) et CCM-3 (comptes dormants) du pack détectent bien
 * les anomalies volontaires du jeu simulé (mouvement n°4 hors seuil, mouvement n°5 sur compte dormant).
 * Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleCcmPopulationGatewayTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private static DriverManagerDataSource dataSource;
    private static CcmPopulationGateway gateway;

    @BeforeAll
    static void applyAndSeed() {
        String url = System.getProperty("it.oracle.url");
        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");

        new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("""
                BEGIN
                  FOR v IN (SELECT view_name FROM user_views) LOOP
                    EXECUTE IMMEDIATE 'DROP VIEW "' || v.view_name || '"';
                  END LOOP;
                  FOR t IN (SELECT table_name FROM user_tables) LOOP
                    EXECUTE IMMEDIATE 'DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE';
                  END LOOP;
                END;
                """);
        run(new ClassPathResource("amplitude-cbs-seed.sql"));
        gateway = new OracleCcmPopulationGateway(dataSource);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void dormantControlFindsMovementOnDormantAccount() {
        // Sans exclusion de canal : le mouvement n°5 (compte DORMANT A002000000003) est détecté.
        List<MovementAnomaly> anomalies = gateway.dormantAccountMovements(
                new DormantAccountCriteria(12, new BigDecimal("10000"), List.of(), FROM, TO));

        assertThat(anomalies).extracting(MovementAnomaly::movementId).contains(5L);
        assertThat(anomalies).allSatisfy(a -> assertThat(a.triggerReason()).isEqualTo("DORMANT"));
        assertThat(anomalies).filteredOn(a -> a.movementId() == 5L).singleElement()
                .satisfies(a -> {
                    assertThat(a.accountId()).isEqualTo("A002000000003");
                    assertThat(a.validatedBy()).isEqualTo("U004");
                });
    }

    @Test
    void dormantControlRespectsChannelExclusion() {
        // Le mouvement n°5 est sur le canal BATCH : en l'excluant, aucune anomalie dormante.
        List<MovementAnomaly> anomalies = gateway.dormantAccountMovements(
                new DormantAccountCriteria(12, new BigDecimal("10000"), List.of("BATCH"), FROM, TO));
        assertThat(anomalies).extracting(MovementAnomaly::movementId).doesNotContain(5L);
    }

    @Test
    void outOfBoundsControlFlagsLargeAmountMovement() {
        // Seuil 1 M MAD, week-end samedi/dimanche, BATCH exclu : seul le mouvement n°4 (2,5 M) ressort.
        List<MovementAnomaly> anomalies = gateway.outOfBoundsMovements(
                new OutOfBoundsCriteria(new BigDecimal("1000000"), List.of("SAT", "SUN"), 2,
                        List.of("BATCH"), FROM, TO));

        assertThat(anomalies).extracting(MovementAnomaly::movementId).contains(4L)
                .doesNotContain(1L, 2L, 3L, 5L);
        assertThat(anomalies).filteredOn(a -> a.movementId() == 4L).singleElement()
                .satisfies(a -> {
                    assertThat(a.amount()).isEqualByComparingTo("2500000");
                    assertThat(a.triggerReason()).isEqualTo("AMOUNT");
                });
    }
}
