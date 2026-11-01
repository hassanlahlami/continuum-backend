package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.continuum.ccm.api.CcmExceptionTriageService.CcmExceptionView;
import com.continuum.ccm.api.CcmExceptionTriageService.IllegalTriageTransitionException;
import com.continuum.ccm.api.CcmExceptionTriageService.JustificationRequiredException;
import com.continuum.ccm.api.CcmExceptionTriageService.StaleCcmExceptionException;
import com.continuum.ccm.api.CcmExceptionTriageService.TriageCommand;
import com.continuum.ccm.api.CcmExceptionTriageService.TriageNotPermittedException;
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
 * Vérifie le triage d'une exception CCM (§5.3, D34) sur base réelle : droit {@code ccm.exception.triage}
 * (portée UNIT_TREE, y compris pour une exception sans unité via la racine), transitions retenir/écarter/
 * affecter/rouvrir, justification obligatoire à l'écart, verrou optimiste, journalisation. Base via
 * {@code -Dit.db.url}.
 */
class JdbcCcmExceptionTriageServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRIAGER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ASSIGNEE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID EXC_UNIT = UUID.fromString("ce000000-0000-0000-0000-0000000000e1");
    private static final UUID EXC_NOUNIT = UUID.fromString("ce000000-0000-0000-0000-0000000000e2");

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
        run(pg.resolve("V202610141000__ccm_run.sql"));
        run(pg.resolve("V202611011000__ccm_exception_run_mission.sql"));
        runCp("ccm-triage-seed.sql");

        auditTrail = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void resetExceptions() {
        jt.update("UPDATE ccm_exception SET status_code='NEW', status_label='À trier', version=0,"
                + " dismissal_justification=NULL, assignee_user_id=NULL, triaged_by=NULL, triaged_at=NULL");
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

    private static JdbcCcmExceptionTriageService service() {
        return new JdbcCcmExceptionTriageService(
                dataSource, new JdbcAuthorizationService(dataSource), auditTrail);
    }

    private static CcmExceptionView load(UUID exceptionId) {
        return jt.queryForObject("""
                SELECT id, tenant_id, org_unit_id, reference, control_code, title, status_code, status_label,
                       criticality_code, assignee_user_id, dismissal_justification, finding_id, version
                  FROM ccm_exception WHERE id = ?""",
                (rs, n) -> new CcmExceptionView(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class), rs.getString("reference"),
                        rs.getString("control_code"), rs.getString("title"), rs.getString("status_code"),
                        rs.getString("status_label"), rs.getString("criticality_code"),
                        rs.getObject("assignee_user_id", UUID.class), rs.getString("dismissal_justification"),
                        rs.getObject("finding_id", UUID.class), rs.getInt("version")),
                exceptionId);
    }

    private static TriageCommand cmd(UUID user, UUID exc, String action, Integer version,
            String justification, UUID assignee) {
        return new TriageCommand(TENANT, user, exc, action, version, justification, assignee);
    }

    @Test
    void retainsAnException() {
        CcmExceptionView result = service().triage(cmd(TRIAGER, EXC_UNIT, "retain", 0, null, null));
        assertThat(result.statusCode()).isEqualTo("RETAINED");
        assertThat(result.version()).isEqualTo(1);
        assertThat(auditTrail.hasActed(TENANT, TRIAGER, "ccm.exception.triage", "CCM_EXCEPTION", EXC_UNIT.toString()))
                .isTrue();
    }

    @Test
    void triagesExceptionWithoutUnitViaTenantRoot() {
        // Exception de contrôle population (org_unit NULL) : le droit UNIT_TREE sur la racine suffit.
        CcmExceptionView result = service().triage(cmd(TRIAGER, EXC_NOUNIT, "retain", 0, null, null));
        assertThat(result.statusCode()).isEqualTo("RETAINED");
    }

    @Test
    void dismissRequiresJustification() {
        assertThatExceptionOfType(JustificationRequiredException.class).isThrownBy(
                () -> service().triage(cmd(TRIAGER, EXC_UNIT, "dismiss", 0, "  ", null)));
        assertThat(load(EXC_UNIT).statusCode()).isEqualTo("NEW");
    }

    @Test
    void dismissesWithJustification() {
        CcmExceptionView result = service().triage(
                cmd(TRIAGER, EXC_UNIT, "dismiss", 0, "Faux positif : opération régularisée.", null));
        assertThat(result.statusCode()).isEqualTo("DISMISSED");
        assertThat(result.dismissalJustification()).contains("Faux positif");
    }

    @Test
    void reopensADismissedException() {
        service().triage(cmd(TRIAGER, EXC_UNIT, "dismiss", 0, "À revoir.", null));
        CcmExceptionView result = service().triage(cmd(TRIAGER, EXC_UNIT, "reopen", 1, null, null));
        assertThat(result.statusCode()).isEqualTo("NEW");
        assertThat(result.dismissalJustification()).isNull();
    }

    @Test
    void assignsAnExceptionWithoutChangingStatus() {
        CcmExceptionView result = service().triage(cmd(TRIAGER, EXC_UNIT, "assign", 0, null, ASSIGNEE));
        assertThat(result.assigneeUserId()).isEqualTo(ASSIGNEE);
        assertThat(result.statusCode()).isEqualTo("NEW");
    }

    @Test
    void rejectsIllegalTransition() {
        // Rouvrir une exception NEW (non écartée) est impossible.
        assertThatExceptionOfType(IllegalTriageTransitionException.class).isThrownBy(
                () -> service().triage(cmd(TRIAGER, EXC_UNIT, "reopen", 0, null, null)));
    }

    @Test
    void rejectsStaleVersion() {
        assertThatExceptionOfType(StaleCcmExceptionException.class).isThrownBy(
                () -> service().triage(cmd(TRIAGER, EXC_UNIT, "retain", 9, null, null)));
    }

    @Test
    void forbidsTriageWithoutFeature() {
        assertThatExceptionOfType(TriageNotPermittedException.class).isThrownBy(
                () -> service().triage(cmd(STRANGER, EXC_UNIT, "retain", 0, null, null)));
        assertThat(load(EXC_UNIT).statusCode()).isEqualTo("NEW");
    }

    @Test
    void offersActionsByStatusAndRight() {
        assertThat(service().availableActions(TENANT, TRIAGER, load(EXC_UNIT)))
                .containsExactlyInAnyOrder("retain", "dismiss", "assign");
        assertThat(service().availableActions(TENANT, STRANGER, load(EXC_UNIT))).isEmpty();

        service().triage(cmd(TRIAGER, EXC_UNIT, "dismiss", 0, "Écartée pour test.", null));
        assertThat(service().availableActions(TENANT, TRIAGER, load(EXC_UNIT))).containsExactly("reopen");
    }
}
