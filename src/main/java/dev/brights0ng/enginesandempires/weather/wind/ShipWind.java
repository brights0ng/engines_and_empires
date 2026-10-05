package dev.brights0ng.enginesandempires.weather.wind;

/**
 * What the wind push remembers about one physics object between ticks. Server thread only.
 */
public final class ShipWind {

    /** The object's outline along its own axes. Built in full once, then kept current block by block. */
    final Silhouette silhouette = new Silhouette();
    boolean built;

    /** Cover on each face (see {@link Shelter}), from the last shelter check. */
    final double[] coverage = new double[Shelter.FACES];
    long nextShelterTick = Long.MIN_VALUE;
    double groundY = Double.NaN;

    /** The game tick this was last brought up to date. */
    long lastTick = Long.MIN_VALUE;

    /** Wind felt, after the gust ramp (m/s), and the unit direction it travels in (world x, z). */
    double speed;
    double dirX;
    double dirZ;

    double aloftShare;
    double pressure = 1;
    double exposure = 1;

    /** The push applied on the last physics step (Sable force units), for the debug command. */
    double lastForce;

    public double speed() {
        return this.speed;
    }

    public double yaw() {
        return WindColumn.yawOf(this.dirX, this.dirZ);
    }

    public double exposure() {
        return this.exposure;
    }

    public double aloftShare() {
        return this.aloftShare;
    }

    public double pressure() {
        return this.pressure;
    }

    public double lastForce() {
        return this.lastForce;
    }

    public double coverage(int face) {
        return this.coverage[face];
    }

    public int area(int axis) {
        return this.silhouette.area(axis);
    }

    public boolean built() {
        return this.built;
    }
}
