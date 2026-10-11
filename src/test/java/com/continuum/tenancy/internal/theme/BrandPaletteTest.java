package com.continuum.tenancy.internal.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Vérifie le cœur HCT (Material 3) et la dérivation de palette de marque (§1.6) : aller-retour HCT,
 * monotonie tonalité→L*, bornes noir/blanc, et surtout <strong>accessibilité</strong> (contraste WCAG AA
 * entre une couleur et son contraste dérivé). Test pur (sans base).
 */
class BrandPaletteTest {

    @Test
    void toneEndpointsAreBlackAndWhite() {
        TonalPalette p = TonalPalette.fromHueAndChroma(200.0, 48.0);
        assertThat(p.tone(0)).isEqualTo(0xFF000000);
        assertThat(p.tone(100)).isEqualTo(0xFFFFFFFF);
    }

    @Test
    void toneMapsToRequestedLstar() {
        TonalPalette p = TonalPalette.fromHueAndChroma(140.0, 48.0);
        // La tonalité HCT est la clarté L* : chaque tonalité doit retrouver son L* à ~1 près.
        for (int tone : new int[] {10, 30, 40, 60, 90}) {
            assertThat(ColorUtils.lstarFromArgb(p.tone(tone))).isCloseTo(tone, within(2.0));
        }
    }

    @Test
    void tonesAreMonotonicInLightness() {
        TonalPalette p = TonalPalette.fromHueAndChroma(260.0, 48.0);
        double prev = -1.0;
        for (int tone = 0; tone <= 100; tone += 10) {
            double lstar = ColorUtils.lstarFromArgb(p.tone(tone));
            assertThat(lstar).isGreaterThan(prev);
            prev = lstar;
        }
    }

    @Test
    void hctRoundTripsAnInGamutColor() {
        int seed = 0xFF0F766E; // teal de la démo
        Cam16 cam = Cam16.fromInt(seed);
        double tone = ColorUtils.lstarFromArgb(seed);
        int back = Hct.toArgb(cam.getHue(), cam.getChroma(), tone);
        assertThat(ColorUtils.redFromArgb(back)).isCloseTo(ColorUtils.redFromArgb(seed), within(5));
        assertThat(ColorUtils.greenFromArgb(back)).isCloseTo(ColorUtils.greenFromArgb(seed), within(5));
        assertThat(ColorUtils.blueFromArgb(back)).isCloseTo(ColorUtils.blueFromArgb(seed), within(5));
    }

    @Test
    void derivesTheExpectedTokenSet() {
        Map<String, String> tokens = BrandPalette.tokensFromSeed("#0f766e");
        assertThat(tokens).containsOnlyKeys(
                "--ct-color-primary", "--ct-color-primary-hover", "--ct-color-primary-contrast",
                "--ct-color-primary-soft", "--ct-color-text", "--ct-color-muted", "--ct-color-border",
                "--ct-color-surface", "--ct-color-surface-alt", "--ct-color-bg");
        assertThat(tokens).allSatisfy((k, v) -> assertThat(v).matches("#[0-9a-f]{6}"));
        assertThat(tokens.get("--ct-color-primary-contrast")).isEqualTo("#ffffff");
    }

    @Test
    void derivedPaletteMeetsWcagAaContrast() {
        Map<String, String> tokens = BrandPalette.tokensFromSeed("#0f766e");
        // Texte sur le primaire, et texte principal sur la surface : AA (≥ 4.5) pour du texte normal.
        assertThat(contrast(tokens.get("--ct-color-primary"), tokens.get("--ct-color-primary-contrast")))
                .isGreaterThanOrEqualTo(4.5);
        assertThat(contrast(tokens.get("--ct-color-text"), tokens.get("--ct-color-surface")))
                .isGreaterThanOrEqualTo(4.5);
    }

    @Test
    void rejectsInvalidSeed() {
        assertThat(BrandPalette.isSeed("#0f766e")).isTrue();
        assertThat(BrandPalette.isSeed("teal")).isFalse();
        assertThatThrownBy(() -> BrandPalette.tokensFromSeed("not-a-color"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double contrast(String hexA, String hexB) {
        double la = relativeLuminance(hexA);
        double lb = relativeLuminance(hexB);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relativeLuminance(String hex) {
        int argb = (int) Long.parseLong("ff" + hex.substring(1), 16);
        return ColorUtils.xyzFromArgb(argb)[1] / 100.0;
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }

    private static org.assertj.core.data.Offset<Integer> within(int v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
