package com.continuum.iam.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Garde-fou de fraîcheur (plan §4.3.5) : le {@code R__feature_catalog.sql} commité dans le
 * {@code continuum-migrator} doit correspondre exactement à la sortie du générateur (donc au registre
 * Java {@link com.continuum.iam.api.Feature}). Toute dérive fait échouer le build.
 *
 * <p>Multi-repo (D38) : le fichier est lu par chemin relatif dans le dépôt voisin ; le test se
 * neutralise si les scripts ne sont pas présents (checkout isolé).
 */
class FeatureCatalogSqlFreshnessTest {

    private static final Path MIGRATOR_DB =
            Path.of("..", "continuum-migrator", "src", "main", "resources", "db").toAbsolutePath().normalize();

    @Test
    void postgresqlCatalogMatchesRegistry() throws IOException {
        assertMatches("postgresql/R__feature_catalog.sql", FeatureCatalogSqlGenerator.postgresql());
    }

    @Test
    void oracleCatalogMatchesRegistry() throws IOException {
        assertMatches("oracle/R__feature_catalog.sql", FeatureCatalogSqlGenerator.oracle());
    }

    private static void assertMatches(String relative, String generated) throws IOException {
        Path file = MIGRATOR_DB.resolve(relative);
        Assumptions.assumeTrue(Files.exists(file), "Script migrateur introuvable (multi-repo) : " + file);
        String committed = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(normalize(committed))
                .as("R__feature_catalog.sql (%s) doit être régénéré depuis le registre Feature", relative)
                .isEqualTo(normalize(generated));
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").stripTrailing();
    }
}
