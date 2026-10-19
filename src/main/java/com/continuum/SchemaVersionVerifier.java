package com.continuum;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Garde-fou de version de schéma au démarrage (plan §4.3.5, D33) : le backend
 * <strong>ne migre pas</strong> (c'est le rôle du {@code continuum-migrator}), mais il refuse de
 * démarrer contre une base dont le schéma est <strong>plus ancien</strong> que la version minimale
 * dont dépend ce code. Complète {@link com.continuum.iam.internal.FeatureCatalogVerifier} (schéma
 * initialisé + catalogue à jour) par un contrôle numérique de la dernière migration appliquée.
 *
 * <p>La version lue est le plus grand numéro de migration <em>versionnée</em> et <em>réussie</em>
 * enregistré par Flyway (table d'historique, par défaut {@code flyway_schema_history}), selon le
 * schéma de nommage du projet {@code Vyyyymmddhhmm__…} (purement numérique). Fail-closed : schéma
 * trop ancien ou historique illisible ⇒ démarrage interrompu avec {@link #EVENT_SCHEMA_OUTDATED}.
 *
 * <p>Paramètres techniques (dictés par l'environnement, donc en properties — cf. CLAUDE.md) :
 * {@code continuum.schema.history-table} (emplacement de l'historique Flyway) et
 * {@code continuum.schema.version-check.enabled} (désactivable pour les bases non gérées par Flyway).
 * La version minimale requise est en revanche un <strong>contrat de code</strong> ({@link #REQUIRED_SCHEMA_VERSION}),
 * à relever lorsque le backend se met à dépendre d'une nouvelle migration.
 */
@Component
public class SchemaVersionVerifier {

    /**
     * Version minimale de schéma dont dépend ce code (dernière migration requise :
     * {@code V202610141000__ccm_run.sql}). À <strong>relever</strong> quand une fonctionnalité du
     * backend commence à dépendre d'une migration plus récente.
     */
    public static final long REQUIRED_SCHEMA_VERSION = 202610141000L;

    /** Schéma plus ancien que {@link #REQUIRED_SCHEMA_VERSION}, ou historique Flyway illisible. */
    public static final String EVENT_SCHEMA_OUTDATED = "CTN-DB-0003";

    private static final Logger log = LoggerFactory.getLogger(SchemaVersionVerifier.class);

    private final JdbcTemplate jdbc;
    private final String historyTable;
    private final boolean enabled;

    public SchemaVersionVerifier(
            DataSource dataSource,
            @Value("${continuum.schema.history-table:flyway_schema_history}") String historyTable,
            @Value("${continuum.schema.version-check.enabled:true}") boolean enabled) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.historyTable = historyTable;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        verify();
    }

    /** Vérifie que la version de schéma appliquée couvre {@link #REQUIRED_SCHEMA_VERSION}. */
    public void verify() {
        if (!enabled) {
            log.info("Schema version guard disabled (continuum.schema.version-check.enabled=false).");
            return;
        }

        long applied = readAppliedVersion();
        if (applied < REQUIRED_SCHEMA_VERSION) {
            throw new IllegalStateException(EVENT_SCHEMA_OUTDATED
                    + " : schéma de base trop ancien (version appliquée " + applied + " < requise "
                    + REQUIRED_SCHEMA_VERSION + "). Exécuter le continuum-migrator avant le backend (§4.3.5).");
        }
        log.info("Schema version verified: applied {} >= required {}.", applied, REQUIRED_SCHEMA_VERSION);
    }

    /** Plus grand numéro de migration versionnée réussie dans l'historique Flyway (0 si aucune). */
    private long readAppliedVersion() {
        try {
            return jdbc.query(
                    "SELECT version, success FROM " + historyTable + " WHERE version IS NOT NULL",
                    rs -> {
                        long max = 0L;
                        while (rs.next()) {
                            if (!rs.getBoolean("success")) {
                                continue;
                            }
                            long v = parseVersion(rs.getString("version"));
                            if (v > max) {
                                max = v;
                            }
                        }
                        return max;
                    });
        } catch (DataAccessException e) {
            throw new IllegalStateException(EVENT_SCHEMA_OUTDATED
                    + " : historique Flyway introuvable ou illisible (table « " + historyTable
                    + " ») — impossible de vérifier la version du schéma. Exécuter le continuum-migrator "
                    + "avant le backend, ou désactiver via continuum.schema.version-check.enabled=false (§4.3.5).", e);
        }
    }

    /** Numéro de version (schéma {@code Vyyyymmddhhmm}, purement numérique) ⇒ {@code long} ; 0 si non numérique. */
    private static long parseVersion(String version) {
        if (version == null) {
            return 0L;
        }
        String digits = version.replace(".", "").trim();
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
