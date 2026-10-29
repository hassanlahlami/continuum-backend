package com.continuum.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Schéma Oracle de test fidèle à la topologie de production (ADR 0012, D39) : deux comptes, aucun
 * privilège ANY. Les scripts {@code platform/} du migrateur tournent sous {@code PLATFORM}, suivis du
 * callback {@code afterMigrate__grant_entity_access.sql} (GRANT SELECT, REFERENCES table par table) ;
 * les scripts {@code entity/} et le backend tournent sous le compte de l'entité ({@code it.oracle.user}).
 *
 * <p>Propriétés : {@code it.oracle.url}, {@code it.oracle.user}/{@code password} (entité),
 * {@code it.oracle.platform.user}/{@code password} (défaut : {@code PLATFORM}/{@code dev-platform},
 * valeurs de {@code infra/simulation} et de {@code ops/oracle-provision.sh}). Si les deux comptes sont
 * identiques (schéma unique de dev), tout passe par la même connexion, sans GRANT.
 */
public final class OracleTestSchema {

    private static final Pattern PLATFORM_WRITE = Pattern.compile(
            "^\\s*(INSERT\\s+INTO|MERGE\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+platform\\.",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ORACLE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,127}");

    private final Path scripts;
    private final String entityUser;
    private final DriverManagerDataSource entity;
    private final DriverManagerDataSource platform;

    private OracleTestSchema(Path scripts, String entityUser,
            DriverManagerDataSource entity, DriverManagerDataSource platform) {
        this.scripts = scripts;
        this.entityUser = entityUser;
        this.entity = entity;
        this.platform = platform;
    }

    /** Connexions entité + platform ; neutralise le test si les scripts du migrateur sont introuvables. */
    public static OracleTestSchema connect() {
        Path scripts = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "oracle")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(scripts), "Scripts Oracle du migrateur introuvables : " + scripts);

        String url = System.getProperty("it.oracle.url");
        String entityUser = System.getProperty("it.oracle.user");
        String platformUser = System.getProperty("it.oracle.platform.user", "PLATFORM");
        if (!ORACLE_IDENTIFIER.matcher(entityUser).matches()) {
            throw new IllegalArgumentException("it.oracle.user n'est pas un identifiant Oracle : " + entityUser);
        }
        DriverManagerDataSource entity = dataSource(url, entityUser, System.getProperty("it.oracle.password"));
        DriverManagerDataSource platform = entityUser.equalsIgnoreCase(platformUser)
                ? entity
                : dataSource(url, platformUser, System.getProperty("it.oracle.platform.password", "dev-platform"));
        return new OracleTestSchema(scripts, entityUser, entity, platform);
    }

    private static DriverManagerDataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        return ds;
    }

    /** Connexion du compte de l'entité (celle du backend). */
    public DriverManagerDataSource dataSource() {
        return entity;
    }

    /** Connexion du propriétaire du schéma partagé, pour écrire des données de test {@code platform.*}. */
    public JdbcTemplate platformJdbc() {
        return new JdbcTemplate(platform);
    }

    /** Supprime vues et tables de l'entité, puis celles de PLATFORM (les FK entité → platform d'abord). */
    public OracleTestSchema reset() {
        dropAll(entity);
        if (platform != entity) {
            dropAll(platform);
        }
        return this;
    }

    private static void dropAll(DriverManagerDataSource ds) {
        new JdbcTemplate(ds).execute("""
                BEGIN
                  FOR v IN (SELECT view_name FROM user_views) LOOP
                    EXECUTE IMMEDIATE 'DROP VIEW "' || v.view_name || '"';
                  END LOOP;
                  FOR t IN (SELECT table_name FROM user_tables) LOOP
                    EXECUTE IMMEDIATE 'DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE';
                  END LOOP;
                END;
                """);
    }

    /** Scripts {@code platform/} sous PLATFORM, puis le callback GRANT du migrateur vers l'entité. */
    public OracleTestSchema platform(String... files) {
        for (String file : files) {
            run(platform, new FileSystemResource(scripts.resolve("platform").resolve(file)));
        }
        if (platform != entity) {
            grantEntityAccess();
        }
        return this;
    }

    /** Scripts {@code entity/} sous le compte de l'entité. */
    public OracleTestSchema entity(String... files) {
        for (String file : files) {
            run(entity, new FileSystemResource(scripts.resolve("entity").resolve(file)));
        }
        return this;
    }

    /**
     * Jeu de données de test : les écritures {@code platform.*} passent par PLATFORM (le backend n'y a
     * qu'un accès en lecture), le reste par l'entité, dans l'ordre du script.
     */
    public OracleTestSchema seed(Resource script) {
        JdbcTemplate entityJdbc = new JdbcTemplate(entity);
        JdbcTemplate platformJdbc = platformJdbc();
        for (String sql : statements(read(script))) {
            (PLATFORM_WRITE.matcher(sql).find() ? platformJdbc : entityJdbc).execute(sql);
        }
        return this;
    }

    /** Découpe un jeu de données DML : commentaires {@code --} en ligne, une instruction finit par {@code ;}. */
    private static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().strip();
                statements.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
        return statements;
    }

    private void grantEntityAccess() {
        String callback = read(new FileSystemResource(
                scripts.resolve("platform").resolve("afterMigrate__grant_entity_access.sql")))
                .replace("${entityUser}", entityUser.toUpperCase(Locale.ROOT))
                .replace("${platformAppUser}", "")
                // Les tests ne chargent qu'une partie des scripts platform : un GRANT ciblé sur une table
                // absente (ORA-00942) n'est pas une erreur ici (le callback de production reste strict).
                .replaceAll("(?m)^([ \t]*)(EXECUTE IMMEDIATE 'GRANT [A-Z, ]+ ON \"[A-Z_]+\" TO [^;]*;)",
                        "$1BEGIN $2 EXCEPTION WHEN OTHERS THEN IF SQLCODE <> -942 THEN RAISE; END IF; END;");
        StringBuilder block = new StringBuilder();
        for (String line : callback.split("\\R")) {
            if (line.strip().equals("/")) {
                platformJdbc().execute(block.toString());
                block.setLength(0);
            } else if (!line.strip().startsWith("--")) {
                block.append(line).append('\n');
            }
        }
    }

    private static void run(DriverManagerDataSource ds, Resource script) {
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static String read(Resource script) {
        try {
            return script.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }
}
