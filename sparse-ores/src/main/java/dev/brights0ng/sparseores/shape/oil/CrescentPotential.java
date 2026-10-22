// Written by Gemini.
// Edited by Gemini: corrected maxRadius calculation and lowered minimum radius constraint.
package dev.brights0ng.sparseores.shape.oil;

/**
 * Crescent / arc trap: oil trapped in structural upturn along the flank of a salt diapir or barrier reef.
 */
public final class CrescentPotential implements OilPotentialField {

    private final int centerX;
    private final int centerZ;
    private final double radius;
    private final double thickness;
    private final double maxAngle;
    private final double cosTheta;
    private final double sinTheta;

    public CrescentPotential(int centerX, int centerZ, double radius, double thickness, double maxAngle, double orientationAngle) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = Math.max(16.0, radius);
        this.thickness = Math.max(5.0, thickness);
        this.maxAngle = Math.max(Math.PI / 6.0, Math.min(Math.PI * 0.75, maxAngle));
        this.cosTheta = Math.cos(orientationAngle);
        this.sinTheta = Math.sin(orientationAngle);
    }

    @Override
    public OilTrapType trapType() {
        return OilTrapType.CRESCENT;
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
        double maxRho = radius + thickness;
        double endU = maxRho * Math.cos(maxAngle) - radius;
        double endV = maxRho * Math.sin(maxAngle);
        return Math.hypot(endU, endV) * 1.05;
    }

    @Override
    public double potential(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double u = dx * cosTheta + dz * sinTheta;
        double v = -dx * sinTheta + dz * cosTheta;

        // Dome center is at (-radius, 0)
        double domeDx = u + radius;
        double domeDz = v;
        double rho = Math.sqrt(domeDx * domeDx + domeDz * domeDz);
        double psi = Math.atan2(domeDz, domeDx);

        if (Math.abs(psi) >= maxAngle) {
            return 0.0;
        }

        double dr = Math.abs(rho - radius);
        if (dr >= thickness) {
            return 0.0;
        }

        double angFrac = psi / maxAngle;
        double tPsi = 1.0 - angFrac * angFrac;
        double rFrac = dr / thickness;
        double tR = 1.0 - rFrac * rFrac;

        double pot = tPsi * tR;
        return Math.max(0.0, Math.min(1.0, pot));
    }

    @Override
    public double[][] polygon(double fullness, int numPoints) {
        if (fullness <= 0.0001) {
            return new double[0][];
        }
        int steps = Math.max(16, numPoints / 2);
        double f = Math.max(0.0, Math.min(1.0, fullness));
        double threshold = 1.0 - f;
        double activeAngle = maxAngle * Math.sqrt(f);

        double[][] pts = new double[steps * 2 + 4][2];
        int idx = 0;

        // Outer arc: -activeAngle to +activeAngle
        for (int i = 0; i <= steps; i++) {
            double frac = (double) i / steps;
            double psi = -activeAngle + 2.0 * activeAngle * frac;
            double angFrac = psi / maxAngle;
            double tPsi = Math.max(0.0001, 1.0 - angFrac * angFrac);
            double deltaR = 0.0;
            if (tPsi > threshold) {
                deltaR = thickness * Math.sqrt(Math.max(0.0, 1.0 - threshold / tPsi));
            }
            double rho = radius + deltaR;
            double u = rho * Math.cos(psi) - radius;
            double v = rho * Math.sin(psi);
            pts[idx][0] = toWorldX(u, v);
            pts[idx++][1] = toWorldZ(u, v);
        }

        // Inner arc: +activeAngle back to -activeAngle
        for (int i = steps; i >= 0; i--) {
            double frac = (double) i / steps;
            double psi = -activeAngle + 2.0 * activeAngle * frac;
            double angFrac = psi / maxAngle;
            double tPsi = Math.max(0.0001, 1.0 - angFrac * angFrac);
            double deltaR = 0.0;
            if (tPsi > threshold) {
                deltaR = thickness * Math.sqrt(Math.max(0.0, 1.0 - threshold / tPsi));
            }
            double rho = radius - deltaR;
            double u = rho * Math.cos(psi) - radius;
            double v = rho * Math.sin(psi);
            pts[idx][0] = toWorldX(u, v);
            pts[idx++][1] = toWorldZ(u, v);
        }

        double[][] result = new double[idx][2];
        System.arraycopy(pts, 0, result, 0, idx);
        return result;
    }

    private double toWorldX(double u, double v) {
        return centerX + u * cosTheta - v * sinTheta;
    }

    private double toWorldZ(double u, double v) {
        return centerZ + u * sinTheta + v * cosTheta;
    }
}
