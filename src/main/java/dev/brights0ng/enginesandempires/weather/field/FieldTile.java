package dev.brights0ng.enginesandempires.weather.field;

import java.util.Arrays;

/**
 * A square of {@link AtmosphereField#SIZE} x {@link AtmosphereField#SIZE} field cells. Arrays are row by row, x
 * fastest. Pure (saved by the server's {@code WeatherSimData}).
 */
public final class FieldTile {

    public final int tx;
    public final int tz;
    /** Surface air temperature at sea level (daily mean), C. */
    public float[] t;
    /** Aloft air temperature (the air ~1.5 km up at real scale), C. */
    public float[] a;
    /** Precipitable water, mm. */
    public float[] q;
    /** Recent precipitation (so far only orographic: water wrung out over rising ground), mm, decaying. */
    public float[] p;
    /** Rain (and snow) the clouds dropped here recently, mm, decaying over half a day (phase 4b). */
    public float[] r;
    /** The climate baseline temperature at each cell, C (refreshed a little at a time; for anomalies and relaxation). */
    public float[] base;
    /** Each cell's biome humidity, 0-1 (refreshed with {@link #base}). */
    public final float[] humidity;
    /** Each cell's surface ordinal (refreshed with {@link #base}). */
    public final byte[] surface;
    /** Terrain elevation above sea level, blocks (NaN until worked out). */
    public final float[] elevation;
    /** Simulation time this tile was last inside a player's zone. */
    public long lastActive;
    /** Simulation time this tile was last stepped (not saved; 0 until the first step after loading). */
    public long lastStepped;

    public FieldTile(int tx, int tz) {
        int n = AtmosphereField.SIZE * AtmosphereField.SIZE;
        this.tx = tx;
        this.tz = tz;
        this.t = new float[n];
        this.a = new float[n];
        this.q = new float[n];
        this.p = new float[n];
        this.r = new float[n];
        this.base = new float[n];
        this.humidity = new float[n];
        this.surface = new byte[n];
        Arrays.fill(humidity, 0.5f);
        this.elevation = new float[n];
        Arrays.fill(elevation, Float.NaN);
    }

    /** World x of cell column {@code i}'s centre. */
    public double cellX(int i) {
        return ((double) tx * AtmosphereField.SIZE + i + 0.5) * AtmosphereField.CELL;
    }

    /** World z of cell row {@code k}'s centre. */
    public double cellZ(int k) {
        return ((double) tz * AtmosphereField.SIZE + k + 0.5) * AtmosphereField.CELL;
    }

    /** Elevation of cell {@code idx}, 0 while unknown. */
    public double elevationOr0(int idx) {
        float e = elevation[idx];
        return Float.isNaN(e) ? 0 : e;
    }
}
