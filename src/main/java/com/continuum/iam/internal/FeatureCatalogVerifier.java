package com.continuum.iam.internal;

import com.continuum.iam.api.Feature;
import com.continuum.iam.api.FeatureCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Contrôle de démarrage (plan §4.3.5, D33) : le backend <strong>ne migre pas et n'écrit pas</strong>
 * la table {@code feature} (compte applicatif potentiellement en lecture seule, §4.3.3). Il
 * <strong>vérifie</strong> que le catalogue en base, peuplé par le {@code R__feature_catalog.sql} du
 * {@code continuum-migrator}, couvre le registre Java {@link Feature} (source de vérité, §2.1.7).
 *
 * <p>Fail-closed : si le schéma n'est pas initialisé ({@link #EVENT_SCHEMA_MISSING}) ou si le
 * catalogue est en dérive ({@link #EVENT_CATALOG_DRIFT}), le démarrage échoue avec un code
 * d'événement explicite (§10.4).
 */
@Component
public class FeatureCatalogVerifier {

    /** Schéma non initialisé : le migrateur doit s'exécuter avant le backend. */
    public static final String EVENT_SCHEMA_MISSING = "CTN-DB-0001";
    /** La table feature ne reflète pas le registre : régénérer/rejouer le catalogue. */
    public static final String EVENT_CATALOG_DRIFT = "CTN-DB-0002";

    private static final Logger log = LoggerFactory.getLogger(FeatureCatalogVerifier.class);

    private final JdbcTemplate jdbc;

    public FeatureCatalogVerifier(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        verify();
    }

    /** Vérifie que toutes les fonctionnalités non dépréciées du registre existent en base. */
    public void verify() {
        List<String> existingCodes;
        try {
            existingCodes = jdbc.queryForList(
                    "SELECT code FROM platform.feature WHERE NOT deprecated", String.class);
        } catch (DataAccessException e) {
            throw new IllegalStateException(EVENT_SCHEMA_MISSING
                    + " : catalogue de fonctionnalités introuvable — le continuum-migrator doit "
                    + "s'exécuter avant le backend (§4.3.5).", e);
        }

        Set<String> present = new HashSet<>(existingCodes);
        Set<String> missing = new TreeSet<>();
        long expected = 0;
        for (Feature f : FeatureCatalog.all()) {
            if (f.deprecated()) {
                continue;
            }
            expected++;
            if (!present.contains(f.code())) {
                missing.add(f.code());
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(EVENT_CATALOG_DRIFT
                    + " : la table feature ne contient pas toutes les fonctionnalités du registre "
                    + "(manquantes : " + missing + "). Régénérer et rejouer R__feature_catalog.sql (§4.3.5).");
        }

        log.info("Feature catalog verified: {} registry features present in database (rows read: {}).",
                expected, existingCodes.size());
    }
}
