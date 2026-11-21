// Written by Claude (GreedyFluids M8b b4: B3, tiles that run beside a player's).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.greedyfluids.grid.CellGrid3D.Side;

/**
 * B3's reach (M8b b4; Bright, 2026-11-17, decisions 12 and 13): which tiles run beside a tile a player is in, so that
 * a room cut by a tile border is simulated on both sides of the cut.
 * <ul>
 *   <li><b>Link (T1, by shape):</b> two face-adjacent tiles are linked when at least N cell pairs across their shared
 *       face hold enclosed air on both sides: both cells air (doors count, as for the flow), both below their own
 *       column's open height ({@link AirflowBorders#enclosed}). The host counts them ({@link Faces}).</li>
 *   <li><b>Reach:</b> from the player's tile, a face-to-face flood over linked faces, inside the 3×3×3 block of tiles
 *       around it. Every tile reached is a secondary. A chain never leaves the block, so a big build pulls in at most
 *       26 tiles and in practice one to three (an x–z corner).</li>
 * </ul>
 * Pure Java: the region manager supplies the counts ({@link AirflowRegions}).
 */
public final class AirflowNeighbourTiles {

    /** What {@code [heat] airflowNeighbourTiles} does. */
    public enum Mode {
        /** No secondaries, and nothing counted. */
        OFF,
        /** Counts which tiles would run (status and the recorder), but opens none. */
        REPORT,
        /** Runs them. */
        ON
    }

    /** The thresholds whose would-run counts are kept in every mode but OFF, so one session settles N. */
    static final int[] REPORTED = {2, 4, 8};

    /** The shared-cell count across a face, from the host. */
    @FunctionalInterface
    interface Faces {
        /**
         * Enclosed cell pairs shared across {@code side} of the tile at (dx, dy, dz) tiles from the player's, each
         * −1 to 1; −1 while the host can't tell yet (a section not read, a chunk not loaded). 0 where the tile across
         * isn't a tile of the world.
         */
        int shared(int dx, int dy, int dz, Side side);
    }

    /** A tile reached, (dx, dy, dz) tiles from the player's, and the count of the face it was reached through. */
    record Reached(int dx, int dy, int dz, int link) {
    }

    /**
     * The tiles reached from the player's at threshold {@code minCells} (at least 1), in the order the flood met them
     * (face neighbours first). The player's own tile is not in the list.
     */
    static List<Reached> reach(Faces faces, int minCells) {
        int need = Math.max(1, minCells);
        boolean[] seen = new boolean[27];
        seen[index(0, 0, 0)] = true;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {0, 0, 0});
        List<Reached> out = new ArrayList<>();
        while (!queue.isEmpty()) {
            int[] t = queue.poll();
            for (Side side : Side.values()) {
                int a = side.axis(), d = side.high() ? 1 : -1;
                int nx = t[0] + (a == 0 ? d : 0), ny = t[1] + (a == 1 ? d : 0), nz = t[2] + (a == 2 ? d : 0);
                if (Math.abs(nx) > 1 || Math.abs(ny) > 1 || Math.abs(nz) > 1 || seen[index(nx, ny, nz)]) {
                    continue;
                }
                int count = faces.shared(t[0], t[1], t[2], side);
                if (count >= need) {
                    seen[index(nx, ny, nz)] = true;
                    out.add(new Reached(nx, ny, nz, count));
                    queue.add(new int[] {nx, ny, nz});
                }
            }
        }
        return out;
    }

    /**
     * A face inside the block, the same from either side: the lower tile's index times 3 plus the axis, 0–80 (the
     * flood asks only for faces between two tiles of the block).
     */
    static int faceKey(int dx, int dy, int dz, Side side) {
        int a = side.axis(), d = side.high() ? 0 : -1;
        return index(dx + (a == 0 ? d : 0), dy + (a == 1 ? d : 0), dz + (a == 2 ? d : 0)) * 3 + a;
    }

    /**
     * {@code faces} answered at most once per face, the same from either side (a face's count doesn't depend on which
     * tile asks), for several thresholds over one block. Unknown answers (−1) are kept too.
     */
    static Faces memo(Faces faces) {
        int[] known = new int[27 * 3];
        java.util.Arrays.fill(known, Integer.MIN_VALUE);
        return (dx, dy, dz, side) -> {
            int k = faceKey(dx, dy, dz, side);
            if (known[k] == Integer.MIN_VALUE) {
                known[k] = faces.shared(dx, dy, dz, side);
            }
            return known[k];
        };
    }

    /** (dx, dy, dz), each −1 to 1, as 0–26. */
    private static int index(int dx, int dy, int dz) {
        return ((dy + 1) * 3 + (dz + 1)) * 3 + (dx + 1);
    }

    private AirflowNeighbourTiles() {
    }
}
