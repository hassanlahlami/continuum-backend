package com.continuum.tenancy.web;

import com.continuum.tenancy.api.ThemeDirectory;
import com.continuum.tenancy.api.ThemeDirectory.TenantTheme;
import com.continuum.tenancy.internal.theme.BrandPalette;
import gen.pub.api.ThemeApi;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feuille CSS publique du thème d'un tenant (plan §1.6.3, contrat {@code public/public.yaml}) :
 * {@code GET /api/public/theme?entity=…} renvoie les variables {@code --ct-*} appliquées à
 * l'exécution, avant même la connexion. Mise en cache par {@code ETag}. Endpoint public (§4.3.3).
 * Implémente l'interface <strong>générée</strong> (D32).
 */
@RestController
@RequestMapping("/api/public")
public class ThemeController implements ThemeApi {

    private final ThemeDirectory themes;

    public ThemeController(ThemeDirectory themes) {
        this.themes = themes;
    }

    @Override
    public ResponseEntity<String> getThemeStylesheet(String entity, String ifNoneMatch) {
        Optional<TenantTheme> theme = themes.findByEntityCode(entity);
        String etag = "\"" + entity + "-" + theme.map(TenantTheme::version).orElse(0) + "\"";

        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        String css = toCss(expand(theme.map(TenantTheme::tokens).orElse(Map.of())));
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/css"))
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .body(css);
    }

    /**
     * Étend une couleur de marque ({@code --ct-seed}) en une rampe Material 3 (§1.6), puis superpose les
     * tokens explicites du tenant (qui restent prioritaires). Sans seed valide, les tokens sont renvoyés tels quels.
     */
    private static Map<String, String> expand(Map<String, String> tokens) {
        String seed = tokens.get(BrandPalette.SEED_TOKEN);
        if (seed == null || !BrandPalette.isSeed(seed)) {
            return tokens;
        }
        Map<String, String> effective = new LinkedHashMap<>(BrandPalette.tokensFromSeed(seed));
        tokens.forEach((name, value) -> {
            if (!BrandPalette.SEED_TOKEN.equals(name)) {
                effective.put(name, value);
            }
        });
        return effective;
    }

    /** Émet {@code :root { --ct-...: ...; }} à partir des tokens (apparence = donnée du tenant, §1.6). */
    private static String toCss(Map<String, String> tokens) {
        StringBuilder sb = new StringBuilder("/* Continuum — thème du tenant (genere, §1.6) */\n:root{\n");
        tokens.forEach((name, value) -> {
            if (name.startsWith("--ct-")) {
                sb.append("  ").append(sanitize(name)).append(':').append(sanitize(value)).append(";\n");
            }
        });
        return sb.append("}\n").toString();
    }

    /** Garde-fou d'injection : on interdit les caractères qui casseraient la déclaration CSS. */
    private static String sanitize(String s) {
        return s.replaceAll("[;{}<>]", "");
    }
}
