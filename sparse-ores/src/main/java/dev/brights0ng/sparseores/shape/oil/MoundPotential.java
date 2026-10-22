// Written by Gemini.
// Edited by Gemini: lowered minimum radius constraint and tightened maxRadius bound.
package dev.brights0ng.sparseores.shape.oil;

/**
 * Lenticular reef mound trap: an organic, multi-lobed carbonate bioherm or reef structure.
 */
public final class MoundPotential implements OilPotentialField {

    private final int centerX;
    private final int centerZ;
    private final double baseRadius;
    private final double a1, theta1;
    private final double a2, theta2;
    private final double a3, theta3;

    public MoundPotential(int centerX, int centerZ, double baseRadius, long seed) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.baseRadius = Math.max(14.0, baseRadius);

        // Derive deterministic lobe offsets from seed
        this.a1 = 0.18 + (((seed & 0xFF) / 255.0) * 0.12);
        this.theta1 = (((seed >>> 8) & 0xFF) / 255.0) * (Math.PI * 2);

        this.a2 = 0.12 + ((((seed >>> 16) & 0xFF) / 255.0) * 0.10);
        this.theta2 = (((seed >>> 24) & 0xFF) / 255.0) * (Math.PI * 2);

        this.a3 = 0.08 + ((((seed >>> 32) & 0xFF) / 255.0) * 0.08);
        this.theta3 = (((seed >>> 40) & 0xFF) / 255.0) * (Math.PI * 2);
    }

    @Override
    public OilTrapType trapType() {
        return OilTrapType.MOUND;
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
        return baseRadius * (1.0 + a1 + a2 + a3) * 1.05;
    }

    private double maxRadiusAt(double alpha) {
        double lobe = 1.0 + a1 * Math.cos(alpha - theta1)
                          + a2 * Math.cos(2.0 * alpha - theta2)
                          + a3 * Math.sin(3.0 * alpha - theta3);
        return baseRadius * Math.max(0.4, lobe);
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
        double maxR = maxRadiusAt(alpha);
        if (d >= maxR) {
            return 0.0;
        }

        double norm = d / maxR;
        return 1.0 - norm * norm;
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
            double r = maxRadiusAt(alpha) * scale;
            pts[i][0] = centerX + r * Math.cos(alpha);
            pts[i][1] = centerZ + r * Math.sin(alpha);
        }
        return pts;
    }
}
