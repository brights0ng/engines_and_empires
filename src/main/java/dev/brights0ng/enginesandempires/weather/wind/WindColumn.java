package dev.brights0ng.enginesandempires.weather.wind;

/**
 * The wind over one spot, as two horizontal vectors in m/s along the direction the wind travels (world x and z).
 *
 * @param surfaceX surface wind, gusts included
 * @param surfaceZ surface wind, gusts included
 * @param aloftX   aloft wind
 * @param aloftZ   aloft wind
 */
public record WindColumn(double surfaceX, double surfaceZ, double aloftX, double aloftZ) {

    public static final WindColumn CALM = new WindColumn(0, 0, 0, 0);

    /** The same wind at every height. */
    public static WindColumn uniform(double x, double z) {
        return new WindColumn(x, z, x, z);
    }

    /** The wind felt at a given share of aloft wind (see {@link WindBlend#aloftShare}): {x, z}. */
    public double[] at(double aloftShare) {
        return new double[]{
                this.surfaceX + (this.aloftX - this.surfaceX) * aloftShare,
                this.surfaceZ + (this.aloftZ - this.surfaceZ) * aloftShare};
    }

    /**
     * A wind vector from a speed and a direction in Minecraft's yaw convention (the one Project Atmosphere uses): the
     * direction the wind travels toward, 0° = south (+Z), 90° = west, 180° = north, 270° = east.
     */
    public static double[] fromYaw(double speed, double yawDegrees) {
        double radians = Math.toRadians(yawDegrees);
        return new double[]{-Math.sin(radians) * speed, Math.cos(radians) * speed};
    }

    /** The yaw (see {@link #fromYaw}) a vector points toward, 0 to 360. */
    public static double yawOf(double x, double z) {
        double yaw = Math.toDegrees(Math.atan2(-x, z));
        return yaw < 0 ? yaw + 360 : yaw;
    }
}
