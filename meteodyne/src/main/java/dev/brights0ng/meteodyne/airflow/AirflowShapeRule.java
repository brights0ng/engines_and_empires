// Written by Claude (GreedyFluids M7b).
package dev.brights0ng.meteodyne.airflow;

import java.util.List;

/**
 * Whether a partial block (a collision shape that doesn't fill its cell) is a solid to GreedyFluids or air it flows
 * through (M7b b2; Claude's rule, not objected to by Bright, 2026-11-14): SOLID if the shape fills at least half the
 * cell, or covers the cell's whole cross-section across a horizontal axis, so air can't pass that way (connected
 * glass panes, slabs, stairs, farmland, beds, chests). AIR otherwise (carpets, low snow, torches, campfires).
 *
 * <p>Fences and iron bars have collision boxes that span the cell where air passes freely; the
 * {@code meteodyne:airflow_air} tag takes them out before this rule ({@link AirflowCells}).
 *
 * <p>The shape is clipped to the cell and sampled at 1/16 block (where block shapes sit): a 1/16 voxel counts as
 * filled when a box covers its centre.
 */
public final class AirflowShapeRule {

    /** Voxels per block edge. */
    static final int N = 16;

    /** Boxes as {minX, minY, minZ, maxX, maxY, maxZ} in block units (a collision shape's AABBs). */
    public static boolean solid(List<double[]> boxes) {
        boolean[] filled = voxels(boxes);
        int count = 0;
        for (boolean f : filled) {
            if (f) count++;
        }
        return 2 * count >= N * N * N || blocksAlong(filled, 0) || blocksAlong(filled, 2);
    }

    /** The share of the cell the boxes fill, 0 to 1, at 1/16 resolution. */
    public static double volume(List<double[]> boxes) {
        int count = 0;
        for (boolean f : voxels(boxes)) {
            if (f) count++;
        }
        return count / (double) (N * N * N);
    }

    /** Voxel (x, y, z) at (y·16 + z)·16 + x is filled when a box, clipped to the cell, covers its centre. */
    static boolean[] voxels(List<double[]> boxes) {
        boolean[] filled = new boolean[N * N * N];
        for (double[] b : boxes) {
            int x0 = first(b[0]), y0 = first(b[1]), z0 = first(b[2]);
            int x1 = first(b[3]), y1 = first(b[4]), z1 = first(b[5]);
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    for (int x = x0; x < x1; x++) {
                        filled[(y * N + z) * N + x] = true;
                    }
                }
            }
        }
        return filled;
    }

    /** The first voxel whose centre is at or past coordinate c, clipped to 0..16. */
    private static int first(double c) {
        return Math.max(0, Math.min(N, (int) Math.ceil(c * N - 0.5)));
    }

    /**
     * Whether every line of voxels along axis (0 = x, 2 = z) crosses a filled voxel: flow along that axis would have
     * to pass through the shape.
     */
    static boolean blocksAlong(boolean[] filled, int axis) {
        for (int y = 0; y < N; y++) {
            for (int u = 0; u < N; u++) {
                boolean hit = false;
                for (int v = 0; v < N && !hit; v++) {
                    int x = axis == 0 ? v : u, z = axis == 0 ? u : v;
                    hit = filled[(y * N + z) * N + x];
                }
                if (!hit) return false;
            }
        }
        return true;
    }

    private AirflowShapeRule() {
    }
}
