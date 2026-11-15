// Written by Claude (GreedyFluids M7d d2).
package dev.brights0ng.meteodyne.airflow;

import java.util.Arrays;
import java.util.function.IntToDoubleFunction;

import dev.brights0ng.greedyfluids.region.ThermalState;

/**
 * A tile's saved temperatures (M7d d2; Bright, 2026-11-15), one {@link Section} per 16³ section, kept in chunk data
 * ({@link AirflowChunkData}).
 * <ul>
 *   <li><b>What:</b> each air cell's temperature and each wall-store block's wall temperature, as offsets from the
 *       background when saved; offsets nearer 0 than {@link #KEEP} are left out (they read as the background).</li>
 *   <li><b>Decay:</b> read back, an offset shrinks by e^(−Δt / {@link #TAU_TICKS}) over the game ticks since it was
 *       saved and is added to the background <i>now</i>, so an area left alone drifts to today's weather.</li>
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

        /** The saved offset of an air (or, with {@code wall}, a wall) value at a local index, decayed to {@code now}; NaN if none. */
        public double offset(int local, boolean wall, long now) {
            int i = Arrays.binarySearch(cells, wall ? local | WALL : local);
            return i < 0 ? Double.NaN : decay(offsets[i], now - tick);
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

    /** Splits a region's thermal state into sections of offsets from its background ({@code background.applyAsDouble(cell)}). */
    public static Section[] pack(ThermalState state, IntToDoubleFunction background, long tick) {
        int nx = state.nx(), ny = state.ny(), nz = state.nz();
        check(nx, ny, nz);
        int nsx = nx / 16, nsy = ny / 16, nsz = nz / 16;
        Section[] out = new Section[nsx * nsy * nsz];
        int[] cells = new int[2 * 4096];
        float[] offsets = new float[2 * 4096];
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
                                if (Float.isFinite(a) && Math.abs(a - bg) >= KEEP) {
                                    cells[n] = local;
                                    offsets[n++] = (float) (a - bg);
                                }
                            }
                        }
                    }
                    // wall entries sort after every air entry: WALL is above any local index
                    for (int local = 0; local < 4096; local++) {
                        int lx = local & 15, lz = (local >> 4) & 15, ly = local >> 8;
                        int c = ((16 * j + ly) * nz + 16 * k + lz) * nx + 16 * i + lx;
                        float s = state.solid(c);
                        double bg = background.applyAsDouble(c);
                        if (Float.isFinite(s) && Math.abs(s - bg) >= KEEP) {
                            cells[n] = local | WALL;
                            offsets[n++] = (float) (s - bg);
                        }
                    }
                    out[(j * nsz + k) * nsx + i] = new Section(tick, Arrays.copyOf(cells, n), Arrays.copyOf(offsets, n));
                }
            }
        }
        return out;
    }

    /**
     * The thermal state saved sections give a region now: each kept value decayed to {@code now} and added to the
     * region's background; NaN where nothing is kept (the region starts there at its background). Sections may be
     * null (nothing saved); returns null when all are.
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
        for (int j = 0; j < nsy; j++) {
            for (int k = 0; k < nsz; k++) {
                for (int i = 0; i < nsx; i++) {
                    Section s = sections[(j * nsz + k) * nsx + i];
                    if (s == null) {
                        continue;
                    }
                    any = true;
                    for (int e = 0; e < s.cells().length; e++) {
                        int local = s.cells()[e] & (WALL - 1);
                        int lx = local & 15, lz = (local >> 4) & 15, ly = local >> 8;
                        int c = ((16 * j + ly) * nz + 16 * k + lz) * nx + 16 * i + lx;
                        float t = (float) (background.applyAsDouble(c) + decay(s.offsets()[e], now - s.tick()));
                        if ((s.cells()[e] & WALL) != 0) {
                            solid[c] = t;
                        } else {
                            air[c] = t;
                        }
                    }
                }
            }
        }
        return any ? new ThermalState(nx, ny, nz, air, solid) : null;
    }

    private static void check(int nx, int ny, int nz) {
        if (nx % 16 != 0 || ny % 16 != 0 || nz % 16 != 0) {
            throw new IllegalArgumentException("not whole sections: " + nx + "×" + ny + "×" + nz);
        }
    }

    private AirflowSaved() {
    }
}
