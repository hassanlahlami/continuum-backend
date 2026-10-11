package com.continuum.tenancy.internal.theme;

/**
 * Conversions de couleur sRGB ↔ XYZ ↔ L* pour la dérivation Material 3 (HCT). Portage fidèle de
 * <em>material-color-utilities</em> (Google, Apache-2.0).
 */
final class ColorUtils {

    private static final double[][] SRGB_TO_XYZ = {
        {0.41233895, 0.35762064, 0.18051042},
        {0.2126, 0.7152, 0.0722},
        {0.01932141, 0.11916382, 0.95034478},
    };

    private static final double[][] XYZ_TO_SRGB = {
        {3.2413774792388685, -1.5376652402851851, -0.49885366846268053},
        {-0.9691452513005321, 1.8758853451067872, 0.04156585616912061},
        {0.05562093689691305, -0.20395524564742123, 1.0571799111220335},
    };

    private static final double[] WHITE_POINT_D65 = {95.047, 100.0, 108.883};

    private ColorUtils() {
    }

    static double[] whitePointD65() {
        return WHITE_POINT_D65;
    }

    static int argbFromRgb(int red, int green, int blue) {
        return 0xFF000000 | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
    }

    static int redFromArgb(int argb) {
        return (argb >> 16) & 0xff;
    }

    static int greenFromArgb(int argb) {
        return (argb >> 8) & 0xff;
    }

    static int blueFromArgb(int argb) {
        return argb & 0xff;
    }

    /** sRGB 0–255 → composante linéaire 0–100. */
    static double linearized(int rgbComponent) {
        double normalized = rgbComponent / 255.0;
        if (normalized <= 0.040449936) {
            return normalized / 12.92 * 100.0;
        }
        return Math.pow((normalized + 0.055) / 1.055, 2.4) * 100.0;
    }

    /** Composante linéaire 0–100 → sRGB 0–255. */
    static int delinearized(double rgbComponent) {
        double normalized = rgbComponent / 100.0;
        double delinear;
        if (normalized <= 0.0031308) {
            delinear = normalized * 12.92;
        } else {
            delinear = 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
        }
        return MathUtils.clampInt(0, 255, (int) Math.round(delinear * 255.0));
    }

    static int argbFromLinrgb(double[] linrgb) {
        return argbFromRgb(delinearized(linrgb[0]), delinearized(linrgb[1]), delinearized(linrgb[2]));
    }

    static double[] xyzFromArgb(int argb) {
        double r = linearized(redFromArgb(argb));
        double g = linearized(greenFromArgb(argb));
        double b = linearized(blueFromArgb(argb));
        return new double[] {
            r * SRGB_TO_XYZ[0][0] + g * SRGB_TO_XYZ[0][1] + b * SRGB_TO_XYZ[0][2],
            r * SRGB_TO_XYZ[1][0] + g * SRGB_TO_XYZ[1][1] + b * SRGB_TO_XYZ[1][2],
            r * SRGB_TO_XYZ[2][0] + g * SRGB_TO_XYZ[2][1] + b * SRGB_TO_XYZ[2][2],
        };
    }

    static int argbFromXyz(double x, double y, double z) {
        double linearR = x * XYZ_TO_SRGB[0][0] + y * XYZ_TO_SRGB[0][1] + z * XYZ_TO_SRGB[0][2];
        double linearG = x * XYZ_TO_SRGB[1][0] + y * XYZ_TO_SRGB[1][1] + z * XYZ_TO_SRGB[1][2];
        double linearB = x * XYZ_TO_SRGB[2][0] + y * XYZ_TO_SRGB[2][1] + z * XYZ_TO_SRGB[2][2];
        return argbFromRgb(delinearized(linearR), delinearized(linearG), delinearized(linearB));
    }

    /** Luminance Y (0–100) pour une clarté L*. */
    static double yFromLstar(double lstar) {
        return 100.0 * labInvf((lstar + 16.0) / 116.0);
    }

    /** Clarté L* d'une couleur ARGB. */
    static double lstarFromArgb(int argb) {
        double y = xyzFromArgb(argb)[1];
        return 116.0 * labF(y / 100.0) - 16.0;
    }

    static int argbFromLstar(double lstar) {
        double y = yFromLstar(lstar);
        int component = delinearized(y);
        return argbFromRgb(component, component, component);
    }

    private static double labF(double t) {
        double e = 216.0 / 24389.0;
        double kappa = 24389.0 / 27.0;
        if (t > e) {
            return Math.cbrt(t);
        }
        return (kappa * t + 16.0) / 116.0;
    }

    private static double labInvf(double ft) {
        double e = 216.0 / 24389.0;
        double kappa = 24389.0 / 27.0;
        double ft3 = ft * ft * ft;
        if (ft3 > e) {
            return ft3;
        }
        return (116.0 * ft - 16.0) / kappa;
    }
}
