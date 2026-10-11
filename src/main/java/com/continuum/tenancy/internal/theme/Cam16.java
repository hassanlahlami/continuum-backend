package com.continuum.tenancy.internal.theme;

/**
 * Modèle d'apparence des couleurs CAM16, base du système HCT de Material 3. Portage fidèle de
 * <em>material-color-utilities</em> (Google, Apache-2.0).
 */
final class Cam16 {

    static final double[][] XYZ_TO_CAM16RGB = {
        {0.401288, 0.650173, -0.051461},
        {-0.250268, 1.204414, 0.045854},
        {-0.002079, 0.048952, 0.953127},
    };

    static final double[][] CAM16RGB_TO_XYZ = {
        {1.8620678, -1.0112547, 0.14918678},
        {0.38752654, 0.62144744, -0.00897398},
        {-0.01584150, -0.03412294, 1.0499644},
    };

    private final double hue;
    private final double chroma;
    private final double j;
    private final double jstar;
    private final double astar;
    private final double bstar;

    private Cam16(double hue, double chroma, double j, double jstar, double astar, double bstar) {
        this.hue = hue;
        this.chroma = chroma;
        this.j = j;
        this.jstar = jstar;
        this.astar = astar;
        this.bstar = bstar;
    }

    double getHue() {
        return hue;
    }

    double getChroma() {
        return chroma;
    }

    double getJ() {
        return j;
    }

    static Cam16 fromInt(int argb) {
        return fromIntInViewingConditions(argb, ViewingConditions.DEFAULT);
    }

    static Cam16 fromIntInViewingConditions(int argb, ViewingConditions vc) {
        double[] xyz = ColorUtils.xyzFromArgb(argb);
        double x = xyz[0];
        double y = xyz[1];
        double z = xyz[2];

        double rT = x * XYZ_TO_CAM16RGB[0][0] + y * XYZ_TO_CAM16RGB[0][1] + z * XYZ_TO_CAM16RGB[0][2];
        double gT = x * XYZ_TO_CAM16RGB[1][0] + y * XYZ_TO_CAM16RGB[1][1] + z * XYZ_TO_CAM16RGB[1][2];
        double bT = x * XYZ_TO_CAM16RGB[2][0] + y * XYZ_TO_CAM16RGB[2][1] + z * XYZ_TO_CAM16RGB[2][2];

        double[] rgbD = vc.getRgbD();
        double rD = rgbD[0] * rT;
        double gD = rgbD[1] * gT;
        double bD = rgbD[2] * bT;

        double rAF = Math.pow(vc.getFl() * Math.abs(rD) / 100.0, 0.42);
        double gAF = Math.pow(vc.getFl() * Math.abs(gD) / 100.0, 0.42);
        double bAF = Math.pow(vc.getFl() * Math.abs(bD) / 100.0, 0.42);
        double rA = MathUtils.signum(rD) * 400.0 * rAF / (rAF + 27.13);
        double gA = MathUtils.signum(gD) * 400.0 * gAF / (gAF + 27.13);
        double bA = MathUtils.signum(bD) * 400.0 * bAF / (bAF + 27.13);

        double a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
        double b = (rA + gA - 2.0 * bA) / 9.0;
        double u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0;
        double p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0;

        double atanDegrees = Math.toDegrees(Math.atan2(b, a));
        double hue = atanDegrees < 0 ? atanDegrees + 360.0 : (atanDegrees >= 360 ? atanDegrees - 360.0 : atanDegrees);
        double hueRadians = Math.toRadians(hue);

        double ac = p2 * vc.getNbb();
        double j = 100.0 * Math.pow(ac / vc.getAw(), vc.getC() * vc.getZ());

        double huePrime = hue < 20.14 ? hue + 360.0 : hue;
        double eHue = 0.25 * (Math.cos(Math.toRadians(huePrime) + 2.0) + 3.8);
        double p1 = 50000.0 / 13.0 * eHue * vc.getNc() * vc.getNcb();
        double t = p1 * Math.hypot(a, b) / (u + 0.305);
        double alpha = Math.pow(t, 0.9) * Math.pow(1.64 - Math.pow(0.29, vc.getN()), 0.73);
        double c = alpha * Math.sqrt(j / 100.0);

        double m = c * vc.getFlRoot();
        double jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j);
        double mstar = 1.0 / 0.0228 * Math.log1p(0.0228 * m);
        double astar = mstar * Math.cos(hueRadians);
        double bstar = mstar * Math.sin(hueRadians);
        return new Cam16(hue, c, j, jstar, astar, bstar);
    }

    static Cam16 fromJch(double j, double c, double h) {
        double jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j);
        double m = c * ViewingConditions.DEFAULT.getFlRoot();
        double mstar = 1.0 / 0.0228 * Math.log1p(0.0228 * m);
        double hueRadians = Math.toRadians(h);
        double astar = mstar * Math.cos(hueRadians);
        double bstar = mstar * Math.sin(hueRadians);
        return new Cam16(h, c, j, jstar, astar, bstar);
    }

    int viewed(ViewingConditions vc) {
        double alpha = (chroma == 0.0 || j == 0.0) ? 0.0 : chroma / Math.sqrt(j / 100.0);
        double t = Math.pow(alpha / Math.pow(1.64 - Math.pow(0.29, vc.getN()), 0.73), 1.0 / 0.9);
        double hRad = Math.toRadians(hue);
        double eHue = 0.25 * (Math.cos(hRad + 2.0) + 3.8);
        double ac = vc.getAw() * Math.pow(j / 100.0, 1.0 / vc.getC() / vc.getZ());
        double p1 = eHue * (50000.0 / 13.0) * vc.getNc() * vc.getNcb();
        double p2 = ac / vc.getNbb();

        double hSin = Math.sin(hRad);
        double hCos = Math.cos(hRad);
        double gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin);
        double a = gamma * hCos;
        double b = gamma * hSin;
        double rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
        double gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
        double bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;

        double rCBase = Math.max(0, 27.13 * Math.abs(rA) / (400.0 - Math.abs(rA)));
        double rC = MathUtils.signum(rA) * (100.0 / vc.getFl()) * Math.pow(rCBase, 1.0 / 0.42);
        double gCBase = Math.max(0, 27.13 * Math.abs(gA) / (400.0 - Math.abs(gA)));
        double gC = MathUtils.signum(gA) * (100.0 / vc.getFl()) * Math.pow(gCBase, 1.0 / 0.42);
        double bCBase = Math.max(0, 27.13 * Math.abs(bA) / (400.0 - Math.abs(bA)));
        double bC = MathUtils.signum(bA) * (100.0 / vc.getFl()) * Math.pow(bCBase, 1.0 / 0.42);

        double[] rgbD = vc.getRgbD();
        double rF = rC / rgbD[0];
        double gF = gC / rgbD[1];
        double bF = bC / rgbD[2];

        double x = rF * CAM16RGB_TO_XYZ[0][0] + gF * CAM16RGB_TO_XYZ[0][1] + bF * CAM16RGB_TO_XYZ[0][2];
        double y = rF * CAM16RGB_TO_XYZ[1][0] + gF * CAM16RGB_TO_XYZ[1][1] + bF * CAM16RGB_TO_XYZ[1][2];
        double z = rF * CAM16RGB_TO_XYZ[2][0] + gF * CAM16RGB_TO_XYZ[2][1] + bF * CAM16RGB_TO_XYZ[2][2];
        return ColorUtils.argbFromXyz(x, y, z);
    }

    /** Distance perceptuelle CAM16-UCS (ΔE′). */
    double distance(Cam16 other) {
        double dJ = jstar - other.jstar;
        double dA = astar - other.astar;
        double dB = bstar - other.bstar;
        double dEPrime = Math.sqrt(dJ * dJ + dA * dA + dB * dB);
        return 1.41 * Math.pow(dEPrime, 0.63);
    }
}
