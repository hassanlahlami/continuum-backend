package com.continuum.tenancy.internal.theme;

/**
 * Couleur HCT (Hue, Chroma, Tone) de Material 3 : conversion depuis des coordonnées perceptuelles vers
 * une couleur sRGB représentable. Portage fidèle de <em>material-color-utilities</em> (Google, Apache-2.0) :
 * recherche de la clarté CAM16 (J) qui atteint la tonalité L* cible, en ramenant le chroma dans le gamut.
 */
final class Hct {

    private static final double CHROMA_SEARCH_ENDPOINT = 0.4;
    private static final double DE_MAX = 1.0;
    private static final double DL_MAX = 0.2;
    private static final double LIGHTNESS_SEARCH_ENDPOINT = 0.01;

    private Hct() {
    }

    /** Couleur sRGB (ARGB) pour la teinte/chroma/tonalité demandés, ramenée dans le gamut. */
    static int toArgb(double hue, double chroma, double tone) {
        return toArgb(hue, chroma, tone, ViewingConditions.DEFAULT);
    }

    static int toArgb(double hue, double chroma, double tone, ViewingConditions vc) {
        if (chroma < 1.0 || Math.round(tone) <= 0.0 || Math.round(tone) >= 100.0) {
            return ColorUtils.argbFromLstar(tone);
        }
        hue = MathUtils.sanitizeDegreesDouble(hue);
        double high = chroma;
        double low = 0.0;
        double mid = chroma;
        boolean isFirst = true;
        Cam16 answer = null;
        while (Math.abs(low - high) >= CHROMA_SEARCH_ENDPOINT) {
            Cam16 possibleAnswer = findCamByJ(hue, mid, tone);
            if (isFirst) {
                if (possibleAnswer != null) {
                    return possibleAnswer.viewed(vc);
                }
                isFirst = false;
                mid = low + (high - low) / 2.0;
                continue;
            }
            if (possibleAnswer == null) {
                high = mid;
            } else {
                answer = possibleAnswer;
                low = mid;
            }
            mid = low + (high - low) / 2.0;
        }
        if (answer == null) {
            return ColorUtils.argbFromLstar(tone);
        }
        return answer.viewed(vc);
    }

    private static Cam16 findCamByJ(double hue, double chroma, double tone) {
        double low = 0.0;
        double high = 100.0;
        double bestdL = 1000.0;
        double bestdE = 1000.0;
        Cam16 bestCam = null;
        while (Math.abs(low - high) > LIGHTNESS_SEARCH_ENDPOINT) {
            double mid = low + (high - low) / 2.0;
            Cam16 camBeforeClip = Cam16.fromJch(mid, chroma, hue);
            int clipped = camBeforeClip.viewed(ViewingConditions.DEFAULT);
            double clippedLstar = ColorUtils.lstarFromArgb(clipped);
            double dL = Math.abs(tone - clippedLstar);
            if (dL < DL_MAX) {
                Cam16 camClipped = Cam16.fromInt(clipped);
                double dE = camClipped.distance(
                        Cam16.fromJch(camClipped.getJ(), camClipped.getChroma(), hue));
                if (dE <= DE_MAX && dE <= bestdE) {
                    bestdL = dL;
                    bestdE = dE;
                    bestCam = camClipped;
                }
            }
            if (bestdL == 0.0 && bestdE == 0.0) {
                break;
            }
            if (clippedLstar < tone) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return bestCam;
    }
}
