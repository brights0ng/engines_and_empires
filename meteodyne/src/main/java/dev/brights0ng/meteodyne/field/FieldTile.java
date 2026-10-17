package dev.brights0ng.meteodyne.field;

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
    /**
     * Terrain elevation above sea level on a finer grid, {@link AtmosphereField#FINE_PER_CELL} samples a cell side
     * ({@link AtmosphereField#FINE_STEP} blocks apart), row by row, x fastest; NaN until worked out (8b follow-up,
     * round 6: upslope lift reads the climb from the real terrain, not cell-centre points 512 blocks apart). Not saved;
     * worked out again after loading.
     */
    public float[] fine;
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
        int f = AtmosphereField.SIZE * AtmosphereField.FINE_PER_CELL;
        this.fine = new float[f * f];
        Arrays.fill(fine, Float.NaN);
    }

    /** World x of cell column {@code i}'s centre. */
    public double cellX(int i) {
        return ((double) tx * AtmosphereField.SIZE + i + 0.5) * AtmosphereField.CELL;
    }

    /** World z of cell row {@code k}'s centre. */
    public double cellZ(int k) {
        return ((double) tz * AtmosphereField.SIZE + k + 0.5) * AtmosphereField.CELL;
    }

    /** World x of fine column {@code j}'s centre. */
    public double fineX(int j) {
        return (double) tx * AtmosphereField.TILE + (j + 0.5) * AtmosphereField.FINE_STEP;
    }

    /** World z of fine row {@code j}'s centre. */
    public double fineZ(int j) {
        return (double) tz * AtmosphereField.TILE + (j + 0.5) * AtmosphereField.FINE_STEP;
    }

    /** Elevation of cell {@code idx}, 0 while unknown. */
    public double elevationOr0(int idx) {
        float e = elevation[idx];
        return Float.isNaN(e) ? 0 : e;
    }

    /** A deep copy (the forecast runs on copies). */
    public FieldTile copy() {
        FieldTile c = new FieldTile(tx, tz);
        c.t = t.clone();
        c.a = a.clone();
        c.q = q.clone();
        c.p = p.clone();
        c.r = r.clone();
        c.base = base.clone();
        System.arraycopy(humidity, 0, c.humidity, 0, humidity.length);
        System.arraycopy(surface, 0, c.surface, 0, surface.length);
        System.arraycopy(elevation, 0, c.elevation, 0, elevation.length);
        c.fine = fine.clone();
        c.lastActive = lastActive;
        c.lastStepped = lastStepped;
        return c;
    }
}
