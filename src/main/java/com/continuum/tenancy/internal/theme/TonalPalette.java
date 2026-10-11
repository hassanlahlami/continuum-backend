package com.continuum.tenancy.internal.theme;

/**
 * Palette tonale Material 3 : une teinte et un chroma fixes, déclinés en tonalités (L* 0–100). Portage
 * fidèle de <em>material-color-utilities</em> (Google, Apache-2.0).
 */
final class TonalPalette {

    private final double hue;
    private final double chroma;

    private TonalPalette(double hue, double chroma) {
        this.hue = hue;
        this.chroma = chroma;
    }

    static TonalPalette fromInt(int argb) {
        Cam16 cam = Cam16.fromInt(argb);
        return new TonalPalette(cam.getHue(), cam.getChroma());
    }

    static TonalPalette fromHueAndChroma(double hue, double chroma) {
        return new TonalPalette(hue, chroma);
    }

    double chroma() {
        return chroma;
    }

    double hue() {
        return hue;
    }

    /** Couleur ARGB à la tonalité {@code tone} (L* 0–100). */
    int tone(int tone) {
        return Hct.toArgb(hue, chroma, tone);
    }
}
