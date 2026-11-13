package com.continuum.audit.internal;

import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.internal.JdbcAuthorizationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** MIS-02 sur PostgreSQL réel ({@code -Dit.db.url}) : types, univers auditable, création de mission. */
class JdbcMissionPlanningServiceTest {

    private static DataSource dataSource;

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void missionWizardScenario() {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"),
                "Base de test absente : lancer avec -Dit.db.url=…");
        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(pg), "Scripts du continuum-migrator introuvables : " + pg);

        dataSource = new DriverManagerDataSource(url, System.getProperty("it.db.user"),
                System.getProperty("it.db.password"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        for (String f : new String[] {"V202609300900__platform_feature_catalog.sql", "R__feature_catalog.sql",
            "V202609301000__entity_iam_structure.sql", "V202609301100__entity_audit_trail.sql",
            "V202610091200__entity_mission.sql", "V202611131000__mission_planning_schema.sql"}) {
            run(new FileSystemResource(pg.resolve(f)));
        }
        run(new ClassPathResource("mission-create-seed.sql"));

        JdbcAuditTrailService trail = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        MissionPlanningScenario.run(new JdbcMissionPlanningService(dataSource, false,
                new JdbcAuthorizationService(dataSource), trail,
                new JdbcMissionPlanningService.InitialState("PLANIFIEE", "Planifiée", "PREPARATION", "Préparation")),
                jdbc);
    }
}
