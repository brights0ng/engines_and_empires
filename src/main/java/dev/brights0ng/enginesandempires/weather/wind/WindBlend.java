package dev.brights0ng.enginesandempires.weather.wind;

/**
 * The small blends the wind sample goes through: between Project Atmosphere's regions, between surface and aloft wind,
 * and the gust ramp.
 */
public final class WindBlend {

    /** The narrowest the surface-to-aloft blend gets, in blocks, so a ship near a mountaintop doesn't see a step. */
    public static final double MIN_BLEND_HEIGHT = 16.0;

    /**
     * Where a point sits among PA's region centres, for bilinear interpolation. PA's region n covers
     * {@code [n·size, (n+1)·size)} and is centred at {@code n·size + size/2} (integer division, as PA does it).
     *
     * @param x0 the region (x index) whose centre is at or west of the point; the other column is x0 + 1
     * @param z0 the region (z index) whose centre is at or north of the point; the other row is z0 + 1
     * @param tx how far from x0's centre toward x0 + 1's, 0 to 1
     * @param tz the same along z
     */
    public record Corners(int x0, int z0, double tx, double tz) {

        /** Blends four corner values: (x0, z0), (x0 + 1, z0), (x0, z0 + 1), (x0 + 1, z0 + 1). */
        public double blend(double v00, double v10, double v01, double v11) {
            double north = v00 + (v10 - v00) * this.tx;
            double south = v01 + (v11 - v01) * this.tx;
            return north + (south - north) * this.tz;
        }
    }

    public static Corners corners(double x, double z, int regionSize) {
        int half = regionSize / 2;
        double gx = (x - half) / regionSize;
        double gz = (z - half) / regionSize;
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        return new Corners(x0, z0, gx - x0, gz - z0);
    }

    /** The block a region is centred on, along one axis. */
    public static int centre(int region, int regionSize) {
        return region * regionSize + regionSize / 2;
    }

    /**
     * How much of the aloft wind is felt at a height: none within {@code surfaceLayer} blocks of the ground, all of it
     * from {@code aloftHeight} up (or from the top of the surface layer plus {@link #MIN_BLEND_HEIGHT}, if that is
     * higher), and a straight blend between.
     */
    public static double aloftShare(double y, double groundY, double surfaceLayer, double aloftHeight) {
        double bottom = groundY + surfaceLayer;
        double top = Math.max(aloftHeight, bottom + MIN_BLEND_HEIGHT);
        if (y <= bottom) {
            return 0;
        }
        if (y >= top) {
            return 1;
        }
        return (y - bottom) / (top - bottom);
    }

    /**
     * One tick of the gust ramp. A rise reaches 95% of the way in {@code rampTicks}; a fall is taken at once (PA already
     * fades its gusts out).
     */
    public static double ramp(double current, double target, int rampTicks) {
        if (target <= current || rampTicks <= 1) {
            return target;
        }
        double step = 1 - Math.pow(0.05, 1.0 / rampTicks);
        return current + (target - current) * step;
    }

    private WindBlend() {
    }
}
