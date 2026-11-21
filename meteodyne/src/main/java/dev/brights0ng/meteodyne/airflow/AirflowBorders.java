// Written by Claude (GreedyFluids M8b b2); edited by Claude (M8b5b: the far side read by position, from regions of any
// size across).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellGrid3D.Boundary;
import dev.brights0ng.greedyfluids.grid.CellGrid3D.FaceBoundary;
import dev.brights0ng.greedyfluids.grid.CellGrid3D.Side;
import dev.brights0ng.greedyfluids.region.AirFrame;

/**
 * Tile borders (M8b, B2; Bright, 2026-11-16). Where a tile's border cuts enclosed air, the air no longer meets the
 * outside there: no air crosses, and heat crosses only to the neighbouring tile's air while that tile runs.
 * <ul>
 *   <li><b>Enclosed:</b> a cut cell below its column's first open height (the heightmap's highest motion-blocking
 *       block, as the sky follow weights take it; Claude's call: sky light alone would leave glass roofs open).</li>
 *   <li><b>Conditions:</b> an enclosed cut cell is EXCHANGE on every side. Elsewhere the side keeps its own: OPEN on
 *       the four side walls and UP, closed on DOWN. Every cut cell is set, solid or not, so a block edit never changes
 *       a condition; only a column's open height does.</li>
 *   <li><b>Far side:</b> the latest frame of the running region holding the cell across the cut, where that is air:
 *       its box's temperature, and its distance from the border, half the box's depth along the normal, so that both
 *       regions use one conductance between their box centres. NaN (insulated) where the cell across isn't air or no
 *       running region holds it. A neighbour starting or stopping changes no mesh. Since M8b5b regions may be boxes of
 *       tiles, so a side can face several regions, each found by position ({@link Beside}).</li>
 * </ul>
 * Each tile works out its conditions from its own columns, so the two sides of a cut can disagree where a roof ends
 * exactly at a border. Cut cells are addressed by their index on the side's face plane
 * ({@link CellGrid3D#faceIndex}). Pure Java (GreedyFluids, no Minecraft).
 */
final class AirflowBorders {

    /** A running region across a side: its latest frame and its origin relative to the reading region's, cells. */
    record Beside(AirFrame frame, int ox, int oy, int oz) {
        /** Whether the cell (x, y, z), in the reading region's cells, lies in this region. */
        boolean holds(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            return lx >= 0 && ly >= 0 && lz >= 0 && lx < frame.nx() && ly < frame.ny() && lz < frame.nz();
        }
    }

    /** The running neighbours' latest frames, read on the air thread. */
    @FunctionalInterface
    interface Neighbours {
        /** The running regions across {@code side} (none running, or not built yet: empty; never null). */
        List<Beside> across(Side side);
    }

    /** No neighbour ever runs: every EXCHANGE face is insulated. */
    static final Neighbours NONE = side -> List.of();

    private AirflowBorders() {
    }

    /**
     * Whether a cut cell at height {@code y} (region cells) is enclosed, under a column whose first open height is
     * {@code top}.
     */
    static boolean enclosed(int y, int top) {
        return y < top;
    }

    /**
     * The cut cells that are EXCHANGE in a tile of nx × ny × nz cells with column tops {@code tops} ({@code z * nx +
     * x}, region cells; null: none).
     */
    static List<FaceBoundary> conditions(int nx, int ny, int nz, int[] tops) {
        return changes(nx, ny, nz, null, tops);
    }

    /**
     * The cut cells whose condition differs between column tops {@code before} and {@code after} (null: no
     * conditions), each with its condition under {@code after}: EXCHANGE, or null for the side's own.
     */
    static List<FaceBoundary> changes(int nx, int ny, int nz, int[] before, int[] after) {
        List<FaceBoundary> out = new ArrayList<>();
        if (before == after || before != null && after != null && Arrays.equals(before, after)) {
            return out;
        }
        for (Side side : Side.values()) {
            int a = side.axis();
            int edge = side.high() ? (a == 0 ? nx : a == 1 ? ny : nz) - 1 : 0;
            if (a == 1) {
                for (int z = 0; z < nz; z++) {
                    for (int x = 0; x < nx; x++) {
                        add(out, side, x, edge, z, before, after, z * nx + x);
                    }
                }
            } else {
                int along = a == 0 ? nz : nx;
                for (int y = 0; y < ny; y++) {
                    for (int i = 0; i < along; i++) {
                        int x = a == 0 ? edge : i, z = a == 0 ? i : edge;
                        add(out, side, x, y, z, before, after, z * nx + x);
                    }
                }
            }
        }
        return out;
    }

    private static void add(List<FaceBoundary> out, Side side, int x, int y, int z, int[] before, int[] after,
            int column) {
        boolean was = before != null && enclosed(y, before[column]);
        boolean now = after != null && enclosed(y, after[column]);
        if (was != now) {
            out.add(new FaceBoundary(side, x, y, z, now ? Boundary.EXCHANGE : null));
        }
    }

    /**
     * The face-plane indices of a side's EXCHANGE cells on {@code grid}, in order.
     */
    static int[] exchangeCells(CellGrid3D grid, Side side) {
        int a = side.axis(), nx = grid.nx(), ny = grid.ny(), nz = grid.nz();
        int edge = side.high() ? (a == 0 ? nx : a == 1 ? ny : nz) - 1 : 0;
        int[] out = new int[grid.faceLength(side)];
        int n = 0;
        for (int i = 0; i < out.length; i++) {
            int x, y, z;
            switch (a) {
                case 0 -> {
                    x = edge;
                    y = i / nz;
                    z = i % nz;
                }
                case 1 -> {
                    x = i % nx;
                    y = edge;
                    z = i / nx;
                }
                default -> {
                    x = i % nx;
                    y = i / nx;
                    z = edge;
                }
            }
            if (grid.faceBoundary(side, x, y, z) == Boundary.EXCHANGE) {
                out[n++] = i;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Reads the far side of a side cell (by face-plane index) of a region of nx × ny × nz cells, from the regions
     * across the side, into {@code temperatures} (C) and {@code distances} (m) at that index: the air in the cell
     * across the cut and half its box's depth along the normal; NaN for both where that cell is solid or no region
     * across holds it.
     */
    static void read(List<Beside> across, Side side, int index, int nx, int ny, int nz, float[] temperatures,
            float[] distances) {
        // the cell across, in this region's cells: one step out of the side
        int x, y, z;
        switch (side.axis()) {
            case 0 -> {
                x = side.high() ? nx : -1;
                y = index / nz;
                z = index % nz;
            }
            case 1 -> {
                x = index % nx;
                y = side.high() ? ny : -1;
                z = index / nx;
            }
            default -> {
                x = index % nx;
                y = index / nx;
                z = side.high() ? nz : -1;
            }
        }
        for (Beside b : across) {
            if (b.holds(x, y, z)) {
                AirFrame frame = b.frame();
                double px = x - b.ox() + 0.5, py = y - b.oy() + 0.5, pz = z - b.oz() + 0.5;
                boolean air = frame.isAir(px, py, pz);
                temperatures[index] = air ? (float) frame.temperature(px, py, pz) : Float.NaN;
                distances[index] = air ? 0.5f * frame.extent(px, py, pz, side.axis()) : Float.NaN;
                return;
            }
        }
        temperatures[index] = Float.NaN;
        distances[index] = Float.NaN;
    }
}
