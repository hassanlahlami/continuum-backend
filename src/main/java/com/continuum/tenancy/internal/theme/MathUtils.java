package com.continuum.tenancy.internal.theme;

/**
 * Utilitaires numériques pour la dérivation de palette Material 3 (HCT). Portage fidèle du cœur
 * <em>material-color-utilities</em> (Google, Apache-2.0) adapté au style du projet (§1.6).
 */
final class MathUtils {

    private MathUtils() {
    }

    static double sanitizeDegreesDouble(double degrees) {
        degrees = degrees % 360.0;
        if (degrees < 0) {
            degrees += 360.0;
        }
        return degrees;
    }

    static double clampDouble(double min, double max, double input) {
        if (input < min) {
            return min;
        }
        if (input > max) {
            return max;
        }
        return input;
    }

    static int clampInt(int min, int max, int input) {
        if (input < min) {
            return min;
        }
        if (input > max) {
            return max;
        }
        return input;
    }

    static double lerp(double start, double stop, double amount) {
        return (1.0 - amount) * start + amount * stop;
    }

    static double signum(double value) {
        if (value < 0) {
            return -1.0;
        }
        if (value > 0) {
            return 1.0;
        }
        return 0.0;
    }
}
