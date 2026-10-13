package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.continuum.ccm.api.CcmExceptionConversionService.AlreadyConvertedException;
import com.continuum.ccm.api.CcmExceptionConversionService.ConversionResult;
import com.continuum.ccm.api.CcmExceptionConversionService.ConvertCommand;
import com.continuum.ccm.api.CcmExceptionConversionService.ConvertNotPermittedException;
import com.continuum.ccm.api.CcmExceptionConversionService.MissionNotFoundException;
import com.continuum.ccm.api.CcmExceptionConversionService.NotRetainedException;
import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.internal.JdbcAuthorizationService;
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
 * Vérifie la conversion exception → constat candidat (§5.3, D34) sur base réelle : droit
 * {@code ccm.exception.convert}, exception RETENUE non déjà convertie, mission existante ; le constat
 * créé (source CCM) porte la FRAP de l'exception et l'exception pointe vers lui. Base via {@code -Dit.db.url}.
 */
class JdbcCcmExceptionConversionServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERTER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID MISSION = UUID.fromString("f0000000-0000-0000-0000-0000000000a1");
    private static final UUID EXC_RETAINED = UUID.fromString("ce000000-0000-0000-0000-0000000000e1");
    private static final UUID EXC_NEW = UUID.fromString("ce000000-0000-0000-0000-0000000000e2");

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

        run(pg.resolve("V202609300900__platform_feature_catalog.sql"));
        run(pg.resolve("R__feature_catalog.sql"));
        run(pg.resolve("V202609301000__entity_iam_structure.sql"));
        run(pg.resolve("V202609301100__entity_audit_trail.sql"));
        run(pg.resolve("V202610091200__entity_mission.sql"));
        run(pg.resolve("V202610091300__entity_finding.sql"));
        run(pg.resolve("V202610091500__finding_frap.sql"));
        run(pg.resolve("V202610121000__ccm_exception.sql"));
        runCp("ccm-convert-seed.sql");

        auditTrail = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void resetExceptions() {
        // Dénouer le lien avant de supprimer le constat (FK ccm_exception.finding_id → finding).
        jt.update("UPDATE ccm_exception SET finding_id = NULL, version = 0");
        jt.update("DELETE FROM finding WHERE source = 'CCM'");
        jt.update("UPDATE ccm_exception SET status_code='RETAINED', status_label='Retenue' WHERE id=?", EXC_RETAINED);
        jt.update("UPDATE ccm_exception SET status_code='NEW', status_label='À trier' WHERE id=?", EXC_NEW);
    }

    private static void run(Path script) {
        execute(new FileSystemResource(script));
    }

    private static void runCp(String name) {
        execute(new ClassPathResource(name));
    }

    private static void execute(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static JdbcCcmExceptionConversionService service() {
        return new JdbcCcmExceptionConversionService(
                dataSource, new JdbcAuthorizationService(dataSource), auditTrail);
    }

    private static ConvertCommand cmd(UUID user, UUID exc) {
        return new ConvertCommand(TENANT, user, exc, MISSION);
    }

    @Test
    void convertsRetainedExceptionIntoMissionFinding() {
        ConversionResult result = service().convert(cmd(CONVERTER, EXC_RETAINED));
        assertThat(result.missionId()).isEqualTo(MISSION);

        // Constat créé dans la mission, source CCM, FRAP reprise de l'exception.
        assertThat(jt.queryForObject("SELECT mission_id FROM finding WHERE id=?", UUID.class, result.findingId()))
                .isEqualTo(MISSION);
        assertThat(jt.queryForObject("SELECT source FROM finding WHERE id=?", String.class, result.findingId()))
                .isEqualTo("CCM");
        assertThat(jt.queryForObject("SELECT fact FROM finding WHERE id=?", String.class, result.findingId()))
                .contains("Mouvement n°4");
        // L'exception pointe désormais vers le constat.
        assertThat(jt.queryForObject("SELECT finding_id FROM ccm_exception WHERE id=?", UUID.class, EXC_RETAINED))
                .isEqualTo(result.findingId());
        assertThat(auditTrail.hasActed(TENANT, CONVERTER, "ccm.exception.convert", "CCM_EXCEPTION",
                EXC_RETAINED.toString())).isTrue();
    }

    @Test
    void refusesToConvertANonRetainedException() {
        assertThatExceptionOfType(NotRetainedException.class).isThrownBy(
                () -> service().convert(cmd(CONVERTER, EXC_NEW)));
        assertThat(jt.queryForObject("SELECT count(*) FROM finding WHERE source='CCM'", Integer.class)).isZero();
    }

    @Test
    void refusesASecondConversion() {
        service().convert(cmd(CONVERTER, EXC_RETAINED));
        assertThatExceptionOfType(AlreadyConvertedException.class).isThrownBy(
                () -> service().convert(cmd(CONVERTER, EXC_RETAINED)));
        assertThat(jt.queryForObject("SELECT count(*) FROM finding WHERE source='CCM'", Integer.class)).isEqualTo(1);
    }

    @Test
    void forbidsConversionWithoutFeature() {
        assertThatExceptionOfType(ConvertNotPermittedException.class).isThrownBy(
                () -> service().convert(cmd(STRANGER, EXC_RETAINED)));
    }

    @Test
    void rejectsUnknownMission() {
        assertThatExceptionOfType(MissionNotFoundException.class).isThrownBy(
                () -> service().convert(new ConvertCommand(TENANT, CONVERTER, EXC_RETAINED,
                        UUID.fromString("f0000000-0000-0000-0000-0000000000ff"))));
    }
}
