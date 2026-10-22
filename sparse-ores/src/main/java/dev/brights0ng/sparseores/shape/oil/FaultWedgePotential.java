// Written by Gemini.
// Edited by Gemini: lowered minimum radius constraint and tightened maxRadius bound.
package dev.brights0ng.sparseores.shape.oil;

/**
 * Fault-bounded wedge trap: reservoir sealed against an impermeable fault scarp on one side,
 * dipping gently away into strata on the other side.
 */
public final class FaultWedgePotential implements OilPotentialField {

    private final int centerX;
    private final int centerZ;
    private final double dipLength; // reach away from fault (along +u)
    private final double faultSpan;  // half-length along the fault (along v)
    private final double cosTheta;
    private final double sinTheta;
    private final double phi;

    public FaultWedgePotential(int centerX, int centerZ, double dipLength, double faultSpan, double faultAngle, long seed) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.dipLength = Math.max(12.0, dipLength);
        this.faultSpan = Math.max(8.0, faultSpan);
        this.cosTheta = Math.cos(faultAngle);
        this.sinTheta = Math.sin(faultAngle);
        this.phi = ((seed & 0xFFFF) / 65535.0) * (Math.PI * 2);
    }

    @Override
    public OilTrapType trapType() {
        return OilTrapType.FAULT_WEDGE;
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
        return Math.hypot(dipLength, faultSpan) * 1.05;
    }

    @Override
    public double potential(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double u = dx * cosTheta + dz * sinTheta; // perpendicular to fault
        double v = -dx * sinTheta + dz * cosTheta; // parallel to fault

        if (u < 0.0 || u >= dipLength) {
            return 0.0;
        }

        double uNorm = u / dipLength;
        double maxV = faultSpan * Math.sqrt(Math.max(0.0, 1.0 - uNorm));
        if (Math.abs(v) >= maxV || maxV <= 0.0001) {
            return 0.0;
        }

        double vRatio = v / maxV;
        double pot = (1.0 - uNorm) * (1.0 - vRatio * vRatio);
        return Math.max(0.0, Math.min(1.0, pot));
    }

    @Override
    public double[][] polygon(double fullness, int numPoints) {
        if (fullness <= 0.0001) {
            return new double[0][];
        }
        int halfPts = Math.max(12, numPoints / 2);
        int totalPts = halfPts * 2 + 2;
        double[][] pts = new double[totalPts][2];

        double f = Math.max(0.0, Math.min(1.0, fullness));
        double threshold = 1.0 - f;
        double maxU = dipLength * f;
        double faultV = faultSpan * Math.sqrt(f);

        // 1. Fault edge: from (0, -faultV) to (0, +faultV)
        int idx = 0;
        pts[idx][0] = toWorldX(0, -faultV);
        pts[idx++][1] = toWorldZ(0, -faultV);

        pts[idx][0] = toWorldX(0, faultV);
        pts[idx++][1] = toWorldZ(0, faultV);

        // 2. Dipping curve from +faultV down to maxU at v=0
        for (int i = 1; i <= halfPts; i++) {
            double frac = (double) i / halfPts;
            double u = maxU * (1.0 - Math.pow(1.0 - frac, 1.5));
            double uNorm = u / dipLength;
            double potAlongU = 1.0 - uNorm;
            double v = 0.0;
            if (potAlongU > threshold) {
                double ratioSq = 1.0 - (threshold / potAlongU);
                v = (faultSpan * Math.sqrt(Math.max(0.0, 1.0 - uNorm))) * Math.sqrt(Math.max(0.0, ratioSq));
            }
            pts[idx][0] = toWorldX(u, v);
            pts[idx++][1] = toWorldZ(u, v);
        }

        // 3. Return curve from maxU back to -faultV
        for (int i = halfPts - 1; i >= 1; i--) {
            double frac = (double) i / halfPts;
            double u = maxU * (1.0 - Math.pow(1.0 - frac, 1.5));
            double uNorm = u / dipLength;
            double potAlongU = 1.0 - uNorm;
            double v = 0.0;
            if (potAlongU > threshold) {
                double ratioSq = 1.0 - (threshold / potAlongU);
                v = -(faultSpan * Math.sqrt(Math.max(0.0, 1.0 - uNorm))) * Math.sqrt(Math.max(0.0, ratioSq));
            }
            pts[idx][0] = toWorldX(u, v);
            pts[idx++][1] = toWorldZ(u, v);
        }

        // Resize to exact points filled
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
