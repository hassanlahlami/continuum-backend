package com.continuum.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.internal.JdbcRoleService;
import gen.admin.iam.model.LocalizedText;
import gen.admin.iam.model.Role;
import gen.admin.iam.model.RoleInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Vérifie {@link RolesController} (contrat {@code admin/tenancy-iam.yaml}) sur base réelle :
 * {@code POST /roles} crée le rôle (201), libellé i18n persisté, opération journalisée. L'auteur vient
 * du jeton. Base via {@code -Dit.db.url}.
 */
class RolesControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static RolesController controller;

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
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(pg.resolve("V202609301100__entity_audit_trail.sql")));

        AuditTrailService audit = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        controller = new RolesController(new JdbcRoleService(dataSource, audit));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(UUID actorUserId) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
                .claim("tenant_id", TENANT.toString())
                .claim("continuum_user_id", actorUserId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void createsRoleReturns201AndPersists() {
        authenticateAs(ACTOR);

        ResponseEntity<Role> response = controller.createRole(new RoleInput()
                .code("VALIDATEUR_CONSTATS")
                .label(new LocalizedText().fr("Valideur de constats"))
                .kind(RoleInput.KindEnum.ORGANISATIONAL));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Role body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getId()).isNotNull();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM role WHERE id = ? AND code = 'VALIDATEUR_CONSTATS' AND kind = 'ORGANISATIONAL'",
                Integer.class, body.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT label ->> 'fr' FROM role WHERE id = ?", String.class, body.getId()))
                .isEqualTo("Valideur de constats");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'admin.role.manage' AND object_id = ?",
                Integer.class, body.getId().toString())).isEqualTo(1);
    }
}
