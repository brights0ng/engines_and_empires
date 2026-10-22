// Written by Gemini.
// Edited by Gemini: lowered minimum radius constraint for pocket reservoirs.
package dev.brights0ng.sparseores.shape.oil;

/**
 * Anticlinal fold trap: an elongated elliptical structural dome aligned with regional tectonic strike.
 */
public final class AnticlinePotential implements OilPotentialField {

    private final int centerX;
    private final int centerZ;
    private final double semiMajor;
    private final double semiMinor;
    private final double cosTheta;
    private final double sinTheta;
    private final double phi1;
    private final double phi2;

    public AnticlinePotential(int centerX, int centerZ, double semiMajor, double semiMinor, double strikeAngle, long seed) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.semiMajor = Math.max(12.0, semiMajor);
        this.semiMinor = Math.max(5.0, semiMinor);
        this.cosTheta = Math.cos(strikeAngle);
        this.sinTheta = Math.sin(strikeAngle);
        this.phi1 = ((seed & 0xFFFF) / 65535.0) * (Math.PI * 2);
        this.phi2 = (((seed >>> 16) & 0xFFFF) / 65535.0) * (Math.PI * 2);
    }

    @Override
    public OilTrapType trapType() {
        return OilTrapType.ANTICLINE;
    }

    @Override
    public int centerX() {
        return centerX;
    }

    @Override
    public int centerZ() {
        return centerZ;
    }

    @Override
    public double maxRadius() {
        return semiMajor * 1.2;
    }

    private double wobble(double alpha) {
        return 1.0 + 0.10 * Math.cos(3 * alpha + phi1) + 0.06 * Math.sin(5 * alpha + phi2);
    }

    @Override
    public double potential(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double u = dx * cosTheta + dz * sinTheta;
        double v = -dx * sinTheta + dz * cosTheta;

        double alpha = Math.atan2(v, u);
        double w = wobble(alpha);
        double normU = u / semiMajor;
        double normV = v / semiMinor;
        double rSq = (normU * normU + normV * normV) / (w * w);

        if (rSq >= 1.0) {
            return 0.0;
        }
        return 1.0 - rSq;
    }

    @Override
    public double[][] polygon(double fullness, int numPoints) {
        if (fullness <= 0.0001) {
            return new double[0][];
        }
        int n = Math.max(24, numPoints);
        double[][] pts = new double[n][2];
        double scale = Math.sqrt(Math.max(0.0, Math.min(1.0, fullness)));

        for (int i = 0; i < n; i++) {
            double alpha = (i * 2.0 * Math.PI) / n;
            double w = wobble(alpha);
            double u = semiMajor * scale * w * Math.cos(alpha);
            double v = semiMinor * scale * w * Math.sin(alpha);

            pts[i][0] = centerX + u * cosTheta - v * sinTheta;
            pts[i][1] = centerZ + u * sinTheta + v * cosTheta;
        }
        return pts;
    }
}
