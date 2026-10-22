// Written by Gemini.
// Edited by Gemini: lowered minimum radius constraint and tightened maxRadius bound.
package dev.brights0ng.sparseores.shape.oil;

/**
 * Sinuous paleochannel ribbon trap: ancient meandering fluvial or deltaic channel sandstone.
 */
public final class RibbonPotential implements OilPotentialField {

    private final int centerX;
    private final int centerZ;
    private final double halfLength;
    private final double halfWidth;
    private final double amplitude;
    private final double waveNumber;
    private final double cosTheta;
    private final double sinTheta;
    private final double phi1;
    private final double phi2;

    public RibbonPotential(int centerX, int centerZ, double length, double width, double channelAngle, long seed) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.halfLength = Math.max(16.0, length / 2.0);
        this.halfWidth = Math.max(5.0, width / 2.0);
        this.cosTheta = Math.cos(channelAngle);
        this.sinTheta = Math.sin(channelAngle);
        this.amplitude = Math.min(halfLength * 0.4, 45.0);
        this.waveNumber = (2.0 * Math.PI) / Math.max(30.0, halfLength * 1.2);
        this.phi1 = ((seed & 0xFFFF) / 65535.0) * (Math.PI * 2);
        this.phi2 = (((seed >>> 16) & 0xFFFF) / 65535.0) * (Math.PI * 2);
    }

    @Override
    public OilTrapType trapType() {
        return OilTrapType.RIBBON;
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
        return Math.hypot(halfLength, amplitude + halfWidth) * 1.05;
    }

    private double spineV(double u) {
        return amplitude * Math.sin(waveNumber * u + phi1) + (amplitude * 0.3) * Math.sin(waveNumber * 2.3 * u + phi2);
    }

    @Override
    public double potential(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double u = dx * cosTheta + dz * sinTheta;
        double v = -dx * sinTheta + dz * cosTheta;

        if (Math.abs(u) >= halfLength) {
            return 0.0;
        }

        double uFrac = u / halfLength;
        double tU = 1.0 - uFrac * uFrac;
        if (tU <= 0.0) {
            return 0.0;
        }

        double sv = spineV(u);
        double dv = Math.abs(v - sv);
        if (dv >= halfWidth) {
            return 0.0;
        }

        double wRatio = dv / halfWidth;
        double pot = tU * (1.0 - wRatio * wRatio);
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
        double uMax = halfLength * Math.sqrt(f);

        double[][] pts = new double[steps * 2 + 4][2];
        int idx = 0;

        // Positive bank: from -uMax to +uMax
        for (int i = 0; i <= steps; i++) {
            double frac = (double) i / steps; // 0..1
            double u = -uMax + 2.0 * uMax * frac;
            double uFrac = u / halfLength;
            double tU = Math.max(0.0001, 1.0 - uFrac * uFrac);
            double w = 0.0;
            if (tU > threshold) {
                w = halfWidth * Math.sqrt(Math.max(0.0, 1.0 - threshold / tU));
            }
            double v = spineV(u) + w;
            pts[idx][0] = toWorldX(u, v);
            pts[idx++][1] = toWorldZ(u, v);
        }

        // Negative bank: from +uMax back to -uMax
        for (int i = steps; i >= 0; i--) {
            double frac = (double) i / steps;
            double u = -uMax + 2.0 * uMax * frac;
            double uFrac = u / halfLength;
            double tU = Math.max(0.0001, 1.0 - uFrac * uFrac);
            double w = 0.0;
            if (tU > threshold) {
                w = halfWidth * Math.sqrt(Math.max(0.0, 1.0 - threshold / tU));
            }
            double v = spineV(u) - w;
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
