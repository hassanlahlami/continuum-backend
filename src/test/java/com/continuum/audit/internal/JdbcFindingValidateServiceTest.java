package com.continuum.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.continuum.audit.api.FindingActionService.ActionNotPermittedException;
import com.continuum.audit.api.FindingActionService.DynamicSodViolationException;
import com.continuum.audit.api.FindingActionService.FindingNotVisibleException;
import com.continuum.audit.api.FindingActionService.StaleFindingException;
import com.continuum.audit.api.FindingActionService.ValidateFindingCommand;
import com.continuum.audit.api.FindingView;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.internal.JdbcAuthorizationService;
import com.continuum.iam.internal.JdbcSegregationOfDutiesService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie la validation d'un constat (§2.1.4, §2.2) sur base réelle : droit via l'équipe de mission
 * ({@code finding.validate}), SoD dynamique interdisant la validation par l'auteur, verrou optimiste
 * et journalisation. Base fournie via {@code -Dit.db.url} (sinon test neutralisé).
 */
class JdbcFindingValidateServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID VALIDATOR = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID READER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID MISSION = UUID.fromString("f0000000-0000-0000-0000-0000000000a1");
    private static final UUID FINDING = UUID.fromString("11110000-0000-0000-0000-000000000001");

    private static DataSource dataSource;
    private static JdbcTemplate jt;
    private static JdbcAuditTrailService auditTrail;

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
        jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(pg.resolve("V202609301100__entity_audit_trail.sql")));
        run(new FileSystemResource(pg.resolve("V202610091200__entity_mission.sql")));
        run(new FileSystemResource(pg.resolve("V202610091300__entity_finding.sql")));
        run(new FileSystemResource(pg.resolve("V202610091500__finding_frap.sql")));
        run(new ClassPathResource("finding-validate-seed.sql"));

        auditTrail = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        // L'auteur (aa) a écrit le constat : journalisé, ce qui déclenche la SoD dynamique à la validation.
        auditTrail.append(new AuditEntry(TENANT, AUTHOR, "finding.write", "FINDING", FINDING.toString(), null));
    }

    @BeforeEach
    void resetFinding() {
        jt.update("UPDATE finding SET status_code='OUVERT', status_label='Ouvert', version=0 WHERE id=?", FINDING);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Échec d'application du script " + script, e);
        }
    }

    private static JdbcFindingActionService service() {
        return new JdbcFindingActionService(dataSource, new JdbcFindingDirectory(dataSource),
                new JdbcAuthorizationService(dataSource),
                new JdbcSegregationOfDutiesService(dataSource, auditTrail), auditTrail);
    }

    @Test
    void validatesForTeamMemberWhoDidNotAuthor() {
        FindingView result = service().validate(
                new ValidateFindingCommand(TENANT, VALIDATOR, MISSION, FINDING, 0, "Revue OK"));

        assertThat(result.statusCode()).isEqualTo("VALIDATED");
        assertThat(result.version()).isEqualTo(1);
        assertThat(jt.queryForObject("SELECT status_code FROM finding WHERE id=?", String.class, FINDING))
                .isEqualTo("VALIDATED");
        assertThat(auditTrail.hasActed(TENANT, VALIDATOR, "finding.validate", "FINDING", FINDING.toString()))
                .isTrue();
    }

    @Test
    void blocksValidationByTheAuthorViaDynamicSod() {
        assertThatExceptionOfType(DynamicSodViolationException.class).isThrownBy(() -> service().validate(
                new ValidateFindingCommand(TENANT, AUTHOR, MISSION, FINDING, 0, null)));

        assertThat(jt.queryForObject("SELECT status_code FROM finding WHERE id=?", String.class, FINDING))
                .isEqualTo("OUVERT");
    }

    @Test
    void forbidsValidationWithoutMissionMembership() {
        assertThatExceptionOfType(ActionNotPermittedException.class).isThrownBy(() -> service().validate(
                new ValidateFindingCommand(TENANT, READER, MISSION, FINDING, 0, null)));
    }

    @Test
    void hidesFindingFromUsersOutsideScope() {
        assertThatExceptionOfType(FindingNotVisibleException.class).isThrownBy(() -> service().validate(
                new ValidateFindingCommand(TENANT, STRANGER, MISSION, FINDING, 0, null)));
    }

    @Test
    void rejectsStaleVersion() {
        assertThatExceptionOfType(StaleFindingException.class).isThrownBy(() -> service().validate(
                new ValidateFindingCommand(TENANT, VALIDATOR, MISSION, FINDING, 5, null)));
    }
}
