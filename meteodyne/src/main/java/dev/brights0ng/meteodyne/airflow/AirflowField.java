// Written by Claude (GreedyFluids M7c c2).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellType;

/**
 * A tile's background field from the server's samples (M7c c2; Bright, 2026-11-15). Pure Java.
 * <ul>
 *   <li><b>Temperature:</b> Meteodyne's air temperature ({@code Temperature.at}: the outside air, blended toward the
 *       underground value by cover and sky light) sampled every {@link #STEP} blocks, corners included, and
 *       interpolated trilinearly to each cell's centre.</li>
 *   <li><b>Follow weights:</b> (sky / 15)², where sky is a sky light computed through the tile's air: 15 in air at or
 *       above its column's top blocking block (the world's heightmap), then one less per step through air, as the
 *       game's light spreads. Solids, glass included, stop it: what counts is air the weather can reach.</li>
 * </ul>
 */
public final class AirflowField {

    /** Lattice spacing, blocks. */
    public static final int STEP = 4;

    /** Lattice points along an axis of {@code cells} cells (both ends included). */
    public static int points(int cells) {
        return cells / STEP + 1;
    }

    /** Per-cell temperatures, C, from a lattice of {@link #points} values per axis, ordered (j·lz + k)·lx + i. */
    public static float[] cells(float[] lattice, int nx, int ny, int nz) {
        int lx = points(nx), ly = points(ny), lz = points(nz);
        if (lattice.length != lx * ly * lz) {
            throw new IllegalArgumentException("expected " + lx * ly * lz + " lattice points, got " + lattice.length);
        }
        float[] out = new float[nx * ny * nz];
        for (int y = 0; y < ny; y++) {
            double fy = (y + 0.5) / STEP;
            int j = Math.min((int) fy, ly - 2);
            double ty = fy - j;
            for (int z = 0; z < nz; z++) {
                double fz = (z + 0.5) / STEP;
                int k = Math.min((int) fz, lz - 2);
                double tz = fz - k;
                for (int x = 0; x < nx; x++) {
                    double fx = (x + 0.5) / STEP;
                    int i = Math.min((int) fx, lx - 2);
                    double tx = fx - i;
                    double c00 = lerp(lattice[(j * lz + k) * lx + i], lattice[(j * lz + k) * lx + i + 1], tx);
                    double c01 = lerp(lattice[(j * lz + k + 1) * lx + i], lattice[(j * lz + k + 1) * lx + i + 1], tx);
                    double c10 = lerp(lattice[((j + 1) * lz + k) * lx + i], lattice[((j + 1) * lz + k) * lx + i + 1], tx);
                    double c11 = lerp(lattice[((j + 1) * lz + k + 1) * lx + i],
                            lattice[((j + 1) * lz + k + 1) * lx + i + 1], tx);
                    out[(y * nz + z) * nx + x] = (float) lerp(lerp(c00, c01, tz), lerp(c10, c11, tz), ty);
                }
            }
        }
        return out;
    }

    /**
     * Sky light through the grid's air, 0–15 per cell: 15 in fluid cells whose height (region cells) is at least
     * {@code top[z * nx + x]} (the column's first open height; NaN-free, may lie outside the grid), then one less per
     * face step through fluid cells. Solid cells are 0.
     */
    public static byte[] sky(CellGrid3D grid, int[] top) {
        int nx = grid.nx(), ny = grid.ny(), nz = grid.nz(), n = nx * ny * nz;
        if (top.length != nx * nz) {
            throw new IllegalArgumentException("expected one column top per column");
        }
        byte[] sky = new byte[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                for (int y = Math.max(0, top[z * nx + x]); y < ny; y++) {
                    int c = (y * nz + z) * nx + x;
                    if (grid.type(x, y, z).isFluid()) {
                        sky[c] = 15;
                        queue[tail++] = c;
                    }
                }
            }
        }
        // Breadth first from every 15: each step is one less, so a cell's first visit is its brightest
        while (head < tail) {
            int c = queue[head++];
            int level = sky[c] - 1;
            if (level <= 0) {
                continue;
            }
            int x = c % nx, z = c / nx % nz, y = c / (nx * nz);
            for (int f = 0; f < 6; f++) {
                int ax = x + (f == 0 ? -1 : f == 1 ? 1 : 0), ay = y + (f == 2 ? -1 : f == 3 ? 1 : 0);
                int az = z + (f == 4 ? -1 : f == 5 ? 1 : 0);
                if (ax < 0 || ay < 0 || az < 0 || ax >= nx || ay >= ny || az >= nz) {
                    continue;
                }
                int a = (ay * nz + az) * nx + ax;
                if (sky[a] < level && grid.type(ax, ay, az) != CellType.SOLID) {
                    sky[a] = (byte) level;
                    queue[tail++] = a;
                }
            }
        }
        return sky;
    }

    /** Follow weights from sky light: (sky / 15)² per cell. */
    public static float[] follow(byte[] sky) {
        float[] out = new float[sky.length];
        for (int c = 0; c < sky.length; c++) {
            float s = sky[c] / 15f;
            out[c] = s * s;
        }
        return out;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private AirflowField() {
    }
}
