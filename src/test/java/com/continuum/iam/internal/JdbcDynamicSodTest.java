package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.api.SodViolation;
import com.continuum.iam.api.SodViolation.Severity;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie la SoD <strong>dynamique</strong> (§2.2) sur base réelle : un utilisateur ayant réalisé
 * une action sur un objet ne peut pas exercer la fonctionnalité en conflit sur ce même objet.
 * Base fournie via {@code -Dit.db.url} (sinon test neutralisé).
 */
class JdbcDynamicSodTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_USER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static DataSource dataSource;

    @BeforeAll
    static void applySchemaAndSeed() {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"),
                "Base de test absente : lancer avec -Dit.db.url=… -Dit.db.user=… -Dit.db.password=…");

        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(pg), "Scripts du continuum-migrator introuvables : " + pg);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.db.user"), System.getProperty("it.db.password"));

        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");

        run(pg.resolve("V202609300900__platform_feature_catalog.sql"));
        run(pg.resolve("R__feature_catalog.sql"));
        run(pg.resolve("V202609301000__entity_iam_structure.sql"));
        run(pg.resolve("V202609301100__entity_audit_trail.sql"));

        // Règle SoD dynamique : on ne valide pas un constat qu'on a écrit (même objet).
        jt.update("""
                INSERT INTO sod_rule(id, tenant_id, feature_a, feature_b, type, severity)
                VALUES (?, ?, 'finding.write', 'finding.validate', 'DYNAMIC', 'BLOCK')
                """, UUID.randomUUID(), TENANT);

        // L'utilisateur a écrit le constat F-1 (journalisé).
        new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8))
                .append(new AuditEntry(TENANT, USER, "finding.write", "FINDING", "F-1", null));
    }

    private static void run(Path script) {
        try (Connection c = dataSource.getConnection()) {
            Resource r = new FileSystemResource(script);
            ScriptUtils.executeSqlScript(c, new EncodedResource(r, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Échec d'application du script " + script, e);
        }
    }

    private static JdbcSegregationOfDutiesService service() {
        return new JdbcSegregationOfDutiesService(dataSource);
    }

    @Test
    void blocksValidatingAnObjectTheUserAuthored() {
        assertThat(service().dynamicConflicts(TENANT, USER, "finding.validate", "FINDING", "F-1"))
                .extracting(SodViolation::featureA, SodViolation::featureB, SodViolation::severity)
                .containsExactly(tuple("finding.write", "finding.validate", Severity.BLOCK));
    }

    @Test
    void allowsValidatingADifferentObject() {
        assertThat(service().dynamicConflicts(TENANT, USER, "finding.validate", "FINDING", "F-2")).isEmpty();
    }

    @Test
    void allowsValidationForAUserWhoDidNotAuthor() {
        assertThat(service().dynamicConflicts(TENANT, OTHER_USER, "finding.validate", "FINDING", "F-1")).isEmpty();
    }
}
