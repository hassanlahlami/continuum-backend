package com.continuum.iam.tooling;

import com.continuum.iam.api.Feature;
import com.continuum.iam.api.FeatureCatalog;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Génère le script Flyway repeatable {@code R__feature_catalog.sql} du {@code continuum-migrator}
 * à partir du registre {@link Feature} (plan §2.1.7, §4.3.5).
 *
 * <p>Le script est <strong>idempotent</strong> (upsert) et marque {@code deprecated} toute
 * fonctionnalité absente du registre, sans jamais la supprimer (§2.1.7). En CI, la fraîcheur du
 * fichier commité est vérifiée contre cette sortie ; toute divergence fait échouer le build.
 *
 * <p>Usage : {@code java com.continuum.iam.tooling.FeatureCatalogSqlGenerator <postgresql|oracle>}
 */
public final class FeatureCatalogSqlGenerator {

    private static final String TABLE = "platform.feature";
    private static final String HEADER = """
            -- GÉNÉRÉ — ne pas éditer à la main.
            -- Source : registre Java com.continuum.iam.api.Feature (plan §2.1.7).
            -- Régénérer : FeatureCatalogSqlGenerator (§4.3.5). La CI échoue si ce fichier diverge du registre.
            """;

    private FeatureCatalogSqlGenerator() {
    }

    public static void main(String[] args) {
        String dialect = args.length > 0 ? args[0] : "postgresql";
        String sql = switch (dialect) {
            case "postgresql" -> postgresql();
            case "oracle" -> oracle();
            default -> throw new IllegalArgumentException("Dialecte inconnu : " + dialect);
        };
        System.out.print(sql);
    }

    /** Upsert PostgreSQL ({@code INSERT ... ON CONFLICT}). */
    public static String postgresql() {
        StringBuilder sb = new StringBuilder(HEADER);
        sb.append('\n');
        sb.append("INSERT INTO ").append(TABLE)
          .append(" (code, module, label_key, scopable, sensitive, deprecated) VALUES\n");
        StringJoiner rows = new StringJoiner(",\n");
        for (Feature f : FeatureCatalog.all()) {
            rows.add("  ('%s', '%s', '%s', %b, %b, %b)"
                    .formatted(f.code(), f.module(), f.labelKey(), f.scopable(), f.sensitive(), f.deprecated()));
        }
        sb.append(rows).append('\n');
        sb.append("ON CONFLICT (code) DO UPDATE SET\n")
          .append("  module     = EXCLUDED.module,\n")
          .append("  label_key  = EXCLUDED.label_key,\n")
          .append("  scopable   = EXCLUDED.scopable,\n")
          .append("  sensitive  = EXCLUDED.sensitive,\n")
          .append("  deprecated = EXCLUDED.deprecated;\n\n");
        sb.append("-- Fonctionnalités retirées du registre : marquées dépréciées, jamais supprimées (§2.1.7).\n");
        sb.append("UPDATE ").append(TABLE).append(" SET deprecated = true\n")
          .append(" WHERE code NOT IN (").append(codeList()).append(");\n");
        return sb.toString();
    }

    /** Upsert Oracle (une instruction {@code MERGE} par ligne, portable). */
    public static String oracle() {
        StringBuilder sb = new StringBuilder(HEADER);
        sb.append('\n');
        for (Feature f : FeatureCatalog.all()) {
            sb.append("MERGE INTO ").append(TABLE).append(" t USING (SELECT ")
              .append("'%s' code, '%s' module, '%s' label_key, %d scopable, %d sensitive, %d deprecated FROM dual"
                      .formatted(f.code(), f.module(), f.labelKey(),
                              b(f.scopable()), b(f.sensitive()), b(f.deprecated())))
              .append(") s ON (t.code = s.code)\n")
              .append("WHEN MATCHED THEN UPDATE SET t.module = s.module, t.label_key = s.label_key, ")
              .append("t.scopable = s.scopable, t.sensitive = s.sensitive, t.deprecated = s.deprecated\n")
              .append("WHEN NOT MATCHED THEN INSERT (code, module, label_key, scopable, sensitive, deprecated) ")
              .append("VALUES (s.code, s.module, s.label_key, s.scopable, s.sensitive, s.deprecated);\n");
        }
        sb.append("\n-- Fonctionnalités retirées du registre : marquées dépréciées, jamais supprimées (§2.1.7).\n");
        sb.append("UPDATE ").append(TABLE).append(" SET deprecated = 1\n")
          .append(" WHERE code NOT IN (").append(codeList()).append(");\n");
        return sb.toString();
    }

    private static int b(boolean value) {
        return value ? 1 : 0;
    }

    private static String codeList() {
        return FeatureCatalog.all().stream()
                .map(f -> "'" + f.code() + "'")
                .collect(Collectors.joining(", "));
    }
}
