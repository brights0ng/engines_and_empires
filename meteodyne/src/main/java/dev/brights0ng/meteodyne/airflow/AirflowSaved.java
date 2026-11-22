// Written by Claude (GreedyFluids M7d d2); edited by Claude (M7f: wall stores saved one by one).
// Edited by Claude: values out of AirflowPlausibility's band are neither saved, restored nor read (2026-11-21).
package dev.brights0ng.meteodyne.airflow;

import java.util.Arrays;
import java.util.function.IntToDoubleFunction;

import dev.brights0ng.greedyfluids.region.ThermalState;

/**
 * A tile's saved temperatures (M7d d2; Bright, 2026-11-15), one {@link Section} per 16³ section, kept in chunk data
 * ({@link AirflowChunkData}).
 * <ul>
 *   <li><b>What:</b> each air cell's temperature and each wall store's temperature (M7f; before, each wall block's
 *       mean over the stores holding it, which mixed them), as offsets from the background when saved; offsets
 *       nearer 0 than {@link #KEEP} are left out (they read as the background).</li>
 *   <li><b>Decay:</b> read back, an offset shrinks by e^(−Δt / {@link #TAU_TICKS}) over the game ticks since it was
 *       saved and is added to the background <i>now</i>, so an area left alone drifts to today's weather.</li>
 *   <li><b>Plausibility (2026-11-21):</b> a temperature out of {@link AirflowPlausibility}'s band is not saved; read
 *       back, one out of it (or an offset no plausible temperature could have) is ignored, so that air starts at its
 *       background. A save written before the guard by a tile whose heat ran away heals when it is loaded.</li>
 * </ul>
 * Pure Java (GreedyFluids, no Minecraft).
 */
public final class AirflowSaved {

    /** The decay time constant: one in-game day, ticks (Bright, 2026-11-15). */
    public static final double TAU_TICKS = 24_000;
    /** Offsets nearer 0 than this aren't kept, C. */
    public static final float KEEP = 0.02f;
    /** Flags a wall entry in {@link Section#cells}; air entries have it clear. */
    public static final int WALL = 1 << 12;
    /**
     * Flags a wall-store entry (M7f): {@code local | STORE | dir << DIR_SHIFT}, the store whose ray leaves the air cell
     * at {@code local} in direction {@code dir} (0..5, 6 a door panel). A state that keeps its stores one by one is
     * saved as these instead of per-block {@link #WALL} entries; saves holding {@link #WALL} entries still load, as
     * block means.
     */
    public static final int STORE = 1 << 13, DIR_SHIFT = 14;
    /** Every entry key is below this. */
    public static final int KEY_LIMIT = 7 << DIR_SHIFT;

    /**
     * One section's saved values at game tick {@code tick}: {@code cells} holds, in ascending order, each entry's
     * local index {@code (y * 16 + z) * 16 + x}, with {@link #WALL} set for a wall value; {@code offsets} the
     * matching offsets from the background, C. Treat as immutable.
     */
    public record Section(long tick, int[] cells, float[] offsets) {
        public Section {
            if (cells.length != offsets.length) {
                throw new IllegalArgumentException("one offset per cell");
            }
        }

        /**
         * The saved offset of an air (or, with {@code wall}, a wall) value at a local index, decayed to {@code now};
         * NaN if none, or if it is no offset a plausible temperature could have ({@link AirflowPlausibility}).
         */
        public double offset(int local, boolean wall, long now) {
            int i = Arrays.binarySearch(cells, wall ? local | WALL : local);
            return i < 0 || !AirflowPlausibility.plausibleOffset(offsets[i]) ? Double.NaN
                    : decay(offsets[i], now - tick);
        }

        public boolean isEmpty() {
            return cells.length == 0;
        }
    }

    /** A tile's sections, ordered {@code (j * nsz + k) * nsx + i} as in {@link AirflowRegionModel.Spec}, and its origin. */
    public record Tile(int x0, int y0, int z0, int nsx, int nsy, int nsz, Section[] sections) {
    }

    /** An offset after {@code elapsed} ticks. */
    public static double decay(double offset, long elapsed) {
        return elapsed <= 0 ? offset : offset * Math.exp(-elapsed / TAU_TICKS);
    }

    /**
     * Splits a region's thermal state into sections of offsets from its background ({@code background.applyAsDouble(cell)}),
     * leaving out values out of {@link AirflowPlausibility}'s band.
     */
    public static Section[] pack(ThermalState state, IntToDoubleFunction background, long tick) {
        int nx = state.nx(), ny = state.ny(), nz = state.nz();
        check(nx, ny, nz);
        int nsx = nx / 16, nsy = ny / 16, nsz = nz / 16;
        Section[] out = new Section[nsx * nsy * nsz];
        // M7f: the stores kept one by one, sorted by section and key: (section << 44) | (key << 24) | store
        long[] stores = new long[state.stores()];
        int ns = 0;
        for (int k = 0; k < state.stores(); k++) {
            int c = state.storeCell(k);
            float t = state.storeT(k);
            if (!AirflowPlausibility.plausible(t) || Math.abs(t - background.applyAsDouble(c)) < KEEP) {
                continue;
            }
            int x = c % nx, z = c / nx % nz, y = c / (nx * nz);
            int section = ((y >> 4) * nsz + (z >> 4)) * nsx + (x >> 4);
            int key = (((y & 15) * 16 + (z & 15)) * 16 + (x & 15)) | STORE | state.storeDir(k) << DIR_SHIFT;
            stores[ns++] = (long) section << 44 | (long) key << 24 | k;
        }
        stores = Arrays.copyOf(stores, ns);
        Arrays.sort(stores);
        int next = 0;
        boolean blocks = state.stores() == 0;
        int[] cells = new int[2 * 4096 + state.stores()];
        float[] offsets = new float[cells.length];
        for (int j = 0; j < nsy; j++) {
            for (int k = 0; k < nsz; k++) {
                for (int i = 0; i < nsx; i++) {
                    int n = 0;
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                int c = ((16 * j + ly) * nz + 16 * k + lz) * nx + 16 * i + lx;
                                int local = (ly * 16 + lz) * 16 + lx;
                                double bg = background.applyAsDouble(c);
                                float a = state.air(c);
                                if (AirflowPlausibility.plausible(a) && Math.abs(a - bg) >= KEEP) {
                                    cells[n] = local;
                                    offsets[n++] = (float) (a - bg);
                                }
                            }
                        }
                    }
                    // wall entries sort after every air entry: WALL is above any local index
                    for (int local = 0; blocks && local < 4096; local++) {
                        int lx = local & 15, lz = (local >> 4) & 15, ly = local >> 8;
                        int c = ((16 * j + ly) * nz + 16 * k + lz) * nx + 16 * i + lx;
                        float s = state.solid(c);
                        double bg = background.applyAsDouble(c);
                        if (AirflowPlausibility.plausible(s) && Math.abs(s - bg) >= KEEP) {
                            cells[n] = local | WALL;
                            offsets[n++] = (float) (s - bg);
                        }
                    }
                    // store entries sort after both: STORE is above any wall key
                    int section = (j * nsz + k) * nsx + i;
                    for (; next < ns && (int) (stores[next] >>> 44) == section; next++) {
                        int store = (int) (stores[next] & 0xFFFFFF);
                        int c = state.storeCell(store);
                        cells[n] = (int) (stores[next] >>> 24 & 0xFFFFF);
                        offsets[n++] = (float) (state.storeT(store) - background.applyAsDouble(c));
                    }
                    out[(j * nsz + k) * nsx + i] = new Section(tick, Arrays.copyOf(cells, n), Arrays.copyOf(offsets, n));
                }
            }
        }
        return out;
    }

    /**
     * The thermal state saved sections give a region now: each kept value decayed to {@code now} and added to the
     * region's background; NaN where nothing is kept, or what is kept comes back out of {@link AirflowPlausibility}'s
     * band (the region starts there at its background). Sections may be null (nothing saved); returns null when all
     * are.
     */
    public static ThermalState unpack(Section[] sections, int nx, int ny, int nz, IntToDoubleFunction background,
            long now) {
        check(nx, ny, nz);
        int nsx = nx / 16, nsy = ny / 16, nsz = nz / 16;
        if (sections.length != nsx * nsy * nsz) {
            throw new IllegalArgumentException("expected " + nsx * nsy * nsz + " sections, got " + sections.length);
        }
        boolean any = false;
        float[] air = new float[nx * ny * nz], solid = new float[air.length];
        Arrays.fill(air, Float.NaN);
        Arrays.fill(solid, Float.NaN);
        int stores = 0;
        for (Section s : sections) {
            for (int e = 0; s != null && e < s.cells().length; e++) {
                stores += (s.cells()[e] & STORE) != 0 ? 1 : 0;
            }
        }
        int[] storeCell = new int[stores];
        byte[] storeDir = new byte[stores];
        float[] storeT = new float[stores];
        int m = 0;
        for (int j = 0; j < nsy; j++) {
            for (int k = 0; k < nsz; k++) {
                for (int i = 0; i < nsx; i++) {
                    Section s = sections[(j * nsz + k) * nsx + i];
                    if (s == null) {
                        continue;
                    }
                    any = true;
                    for (int e = 0; e < s.cells().length; e++) {
                        int key = s.cells()[e], local = key & (WALL - 1);
                        int lx = local & 15, lz = (local >> 4) & 15, ly = local >> 8;
                        int c = ((16 * j + ly) * nz + 16 * k + lz) * nx + 16 * i + lx;
                        float t = (float) (background.applyAsDouble(c) + decay(s.offsets()[e], now - s.tick()));
                        if (!AirflowPlausibility.plausible(t)) {
                            continue; // a value that ran away (saved before the guard) starts at the background
                        }
                        if ((key & STORE) != 0) {
                            storeCell[m] = c;
                            storeDir[m] = (byte) (key >> DIR_SHIFT);
                            storeT[m++] = t;
                        } else if ((key & WALL) != 0) {
                            solid[c] = t;
                        } else {
                            air[c] = t;
                        }
                    }
                }
            }
        }
        return any ? new ThermalState(nx, ny, nz, air, solid, storeCell, storeDir, storeT) : null;
    }

    private static void check(int nx, int ny, int nz) {
        if (nx % 16 != 0 || ny % 16 != 0 || nz % 16 != 0) {
            throw new IllegalArgumentException("not whole sections: " + nx + "×" + ny + "×" + nz);
        }
    }

    private AirflowSaved() {
    }
}
