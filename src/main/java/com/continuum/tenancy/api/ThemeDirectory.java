package com.continuum.tenancy.api;

import java.util.Map;
import java.util.Optional;

/**
 * Thème publié d'un tenant (plan §1.6) : jeu de tokens {@code --ct-*} appliqués à l'exécution. Sert
 * la feuille CSS publique ({@code GET /api/public/theme}) ; l'apparence est une donnée du tenant,
 * jamais codée en dur dans le front.
 */
public interface ThemeDirectory {

    /** Thème d'un tenant : ses tokens {@code --ct-*} et sa version (pour l'ETag de cache). */
    record TenantTheme(Map<String, String> tokens, int version) {
    }

    /** Le thème publié du tenant identifié par son code (ex. {@code BANQUE-DEMO}), s'il existe. */
    Optional<TenantTheme> findByEntityCode(String entityCode);
}
