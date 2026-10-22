// Written by Gemini.
// Edited by Gemini: lowered minimum radius constraint and tightened maxRadius bound.
package dev.brights0ng.sparseores.shape.oil;

/**
 * Continuous shale basin sheet trap: broad, regional low-permeability petroleum accumulation.
 */
public final class SheetPotential implements OilPotentialField {

    private final int centerX;
    private final int centerZ;
    private final double radius;
    private final double phi1;
    private final double phi2;

    public SheetPotential(int centerX, int centerZ, double radius, long seed) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = Math.max(16.0, radius);
        this.phi1 = ((seed & 0xFFFF) / 65535.0) * (Math.PI * 2);
        this.phi2 = (((seed >>> 16) & 0xFFFF) / 65535.0) * (Math.PI * 2);
    }

    @Override
    public OilTrapType trapType() {
        return OilTrapType.SHEET;
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
        return radius * 1.15;
    }

    private double wobble(double alpha) {
        return 1.0 + 0.08 * Math.cos(2.0 * alpha + phi1) + 0.06 * Math.sin(4.0 * alpha + phi2);
    }

    @Override
    public double potential(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d >= maxRadius()) {
            return 0.0;
        }

        double alpha = Math.atan2(dz, dx);
        double maxR = radius * wobble(alpha);
        if (d >= maxR) {
            return 0.0;
        }

        double norm = d / maxR;
        double norm4 = norm * norm * norm * norm;
        return 1.0 - norm4;
    }

    @Override
    public double[][] polygon(double fullness, int numPoints) {
        if (fullness <= 0.0001) {
            return new double[0][];
        }
        int n = Math.max(24, numPoints);
        double[][] pts = new double[n][2];
        // r = maxR * f^0.25
        double f = Math.max(0.0, Math.min(1.0, fullness));
        double scale = Math.pow(f, 0.25);

        for (int i = 0; i < n; i++) {
            double alpha = (i * 2.0 * Math.PI) / n;
            double r = radius * wobble(alpha) * scale;
            pts[i][0] = centerX + r * Math.cos(alpha);
            pts[i][1] = centerZ + r * Math.sin(alpha);
        }
        return pts;
    }
}
