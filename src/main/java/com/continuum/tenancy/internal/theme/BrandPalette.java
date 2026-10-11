package com.continuum.tenancy.internal.theme;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dérive un jeu de tokens {@code --ct-*} à partir d'une <strong>couleur de marque</strong> (seed), selon
 * Material 3 (§1.6). Le tenant ne fournit qu'une couleur ; le serveur en déduit une rampe cohérente et
 * accessible (clarté L* contrôlée) via les palettes tonales HCT : primaire (chroma relevé à 48),
 * neutre (chroma 4) et neutre-variant (chroma 8), toutes sur la teinte de la marque.
 *
 * <p>Palette claire (schéma « light ») : les rôles M3 sont mappés sur les tokens sémantiques du front.
 * Les tokens explicites du tenant restent prioritaires (ils surchargent la dérivation côté appelant).
 */
public final class BrandPalette {

    /** Clé de token portant la couleur de marque (ex. {@code "--ct-seed": "#0f766e"}). */
    public static final String SEED_TOKEN = "--ct-seed";

    private BrandPalette() {
    }

    /**
     * Tokens {@code --ct-*} dérivés de la couleur {@code seedHex} ({@code #rrggbb} ou {@code #aarrggbb}).
     *
     * @throws IllegalArgumentException si {@code seedHex} n'est pas une couleur hexadécimale valide
     */
    public static Map<String, String> tokensFromSeed(String seedHex) {
        int seed = parseHex(seedHex);
        double hue = Cam16.fromInt(seed).getHue();
        double seedChroma = Cam16.fromInt(seed).getChroma();

        TonalPalette primary = TonalPalette.fromHueAndChroma(hue, Math.max(seedChroma, 48.0));
        TonalPalette neutral = TonalPalette.fromHueAndChroma(hue, 4.0);
        TonalPalette neutralVariant = TonalPalette.fromHueAndChroma(hue, 8.0);

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("--ct-color-primary", hex(primary.tone(40)));
        tokens.put("--ct-color-primary-hover", hex(primary.tone(30)));
        tokens.put("--ct-color-primary-contrast", hex(primary.tone(100)));
        tokens.put("--ct-color-primary-soft", hex(primary.tone(90)));
        tokens.put("--ct-color-text", hex(neutral.tone(10)));
        tokens.put("--ct-color-muted", hex(neutralVariant.tone(40)));
        tokens.put("--ct-color-border", hex(neutralVariant.tone(80)));
        tokens.put("--ct-color-surface", hex(neutral.tone(99)));
        tokens.put("--ct-color-surface-alt", hex(neutralVariant.tone(94)));
        tokens.put("--ct-color-bg", hex(neutral.tone(98)));
        return tokens;
    }

    /** Vrai si la valeur est une couleur de seed exploitable (hexadécimale). */
    public static boolean isSeed(String value) {
        try {
            parseHex(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int parseHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("Couleur de marque absente");
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (!s.matches("(?i)[0-9a-f]{6}|[0-9a-f]{8}")) {
            throw new IllegalArgumentException("Couleur de marque invalide : " + hex);
        }
        if (s.length() == 6) {
            s = "ff" + s;
        }
        return (int) Long.parseLong(s, 16);
    }

    private static String hex(int argb) {
        return String.format("#%02x%02x%02x",
                ColorUtils.redFromArgb(argb), ColorUtils.greenFromArgb(argb), ColorUtils.blueFromArgb(argb));
    }
}
