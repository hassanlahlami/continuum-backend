package com.continuum.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.tenancy.internal.JdbcEntityActionService;
import gen.admin.iam.model.ActionRequest;
import gen.admin.iam.model.Entity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Frontière HTTP de {@link EntitiesController#performEntityAction} sur base PostgreSQL réelle
 * ({@code -Dit.db.url}) : transitions suspend / reactivate / maintenance, {@code availableActions},
 * 404 / 409 / 422, activation conjointe de {@code platform.tenant.active}, journalisation.
 */
class EntitiesControllerTest {

    private static final UUID ACTOR_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static DataSource dataSource;
    private static JdbcTemplate jt;
    private static EntitiesController controller;

    @BeforeAll
    static void applyAndSeed() {
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
        run(pg.resolve("V202609301000__entity_iam_structure.sql"));
        run(pg.resolve("V202609301100__entity_audit_trail.sql"));
        run(pg.resolve("V202609301200__platform_tenant.sql"));
        run(pg.resolve("V202610271000__platform_tenant_status_version.sql"));
        jt.update("INSERT INTO platform.tenant(id, code, label) VALUES (?, 'ADMIN', 'Admin')", ACTOR_TENANT);

        controller = new EntitiesController(
                new JdbcEntityActionService(dataSource,
                        new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8))));
    }

    @BeforeEach
    void resetEntity() {
        jt.update("DELETE FROM platform.tenant WHERE id = ?", ENTITY);
        jt.update("INSERT INTO platform.tenant(id, code, label) VALUES (?, 'FILIALE-01', 'Filiale 01')", ENTITY);
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
                .claim("tenant_id", ACTOR_TENANT.toString())
                .claim("continuum_user_id", ACTOR.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void run(Path script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c,
                    new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static Entity act(String code, Integer version) {
        return controller.performEntityAction(ENTITY, code, new ActionRequest().version(version)).getBody();
    }

    private static HttpStatus statusOfFailure(String code, Integer version) {
        return (HttpStatus) ((ResponseStatusException) org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> act(code, version))).getStatusCode();
    }

    @Test
    void suspendDeactivatesAndOffersReactivateOnly() {
        Entity e = act("suspend", 0);
        assertThat(e.getStatus()).isEqualTo(Entity.StatusEnum.SUSPENDED);
        assertThat(e.getVersion()).isEqualTo(1);
        assertThat(e.getAvailableActions()).extracting(a -> a.getCode()).containsExactly("reactivate");
        assertThat(jt.queryForObject("SELECT active FROM platform.tenant WHERE id = ?", Boolean.class, ENTITY))
                .isFalse();
    }

    @Test
    void maintenanceThenReactivateRestoresActive() {
        Entity m = act("maintenance", 0);
        assertThat(m.getStatus()).isEqualTo(Entity.StatusEnum.MAINTENANCE);
        assertThat(m.getAvailableActions()).extracting(a -> a.getCode()).containsExactly("reactivate", "suspend");
        Entity r = act("reactivate", 1);
        assertThat(r.getStatus()).isEqualTo(Entity.StatusEnum.ACTIVE);
        assertThat(r.getAvailableActions()).extracting(a -> a.getCode()).containsExactly("maintenance", "suspend");
        assertThat(jt.queryForObject("SELECT active FROM platform.tenant WHERE id = ?", Boolean.class, ENTITY))
                .isTrue();
    }

    @Test
    void invalidTransitionIsConflict() {
        assertThat(statusOfFailure("reactivate", 0)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void staleVersionIsConflictAndLeavesStatus() {
        assertThat(statusOfFailure("suspend", 7)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jt.queryForObject("SELECT status FROM platform.tenant WHERE id = ?", String.class, ENTITY))
                .isEqualTo("ACTIVE");
    }

    @Test
    void unknownActionIsUnprocessable() {
        assertThat(statusOfFailure("frobnicate", 0)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void unknownEntityIsNotFound() {
        assertThatThrownBy(() -> controller.performEntityAction(
                UUID.randomUUID(), "suspend", new ActionRequest().version(0)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void actionIsAudited() {
        Integer before = jt.queryForObject("SELECT count(*) FROM audit_event WHERE object_id = ?",
                Integer.class, ENTITY.toString());
        act("suspend", 0);
        assertThat(jt.queryForObject("SELECT count(*) FROM audit_event WHERE object_id = ?",
                Integer.class, ENTITY.toString())).isEqualTo(before + 1);
    }
}
