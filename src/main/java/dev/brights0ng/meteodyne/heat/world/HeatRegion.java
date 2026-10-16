package dev.brights0ng.meteodyne.heat.world;

import java.util.List;

import dev.brights0ng.paradynelib.heat.air.AirGrid;
import dev.brights0ng.paradynelib.heat.air.AirParams;

/**
 * One region of the heat simulation in the world (weather phase 8f-2): a box of world blocks (its outermost layer is
 * the boundary, held at the outside air), the {@link AirGrid} that simulates it, and what the main thread has made
 * ready for its next step. The grid is only touched by the job stepping it; the main thread hands each job its
 * inputs and reads only {@link #published}.
 */
public final class HeatRegion {

    /**
     * A region's blocks as cells, laid out like {@link AirGrid#index}; {@code cover} holds, for each air cell, which of
     * its faces have a solid block within the ships' shelter reach ({@link #COVER_TOP} and the rest).
     */
    public record Geometry(byte[] kinds, float[] conductances, float[] capacities, byte[] openFaces, byte[] cover) {
    }

    /** {@link Geometry#cover} bits. */
    public static final int COVER_TOP = 1, COVER_NORTH = 2, COVER_SOUTH = 4, COVER_WEST = 8, COVER_EAST = 16;

    /**
     * A finished step's field, safe to read from any thread.
     *
     * @param wind     each cell's share of the wind, 0-1
     * @param gameTime when the step that made it was started
     */
    public record Published(int minX, int minY, int minZ, int nx, int ny, int nz, float[] offsets, byte[] kinds,
                            float[] wind, long gameTime) {

        public int index(int x, int y, int z) {
            return (z * ny + y) * nx + x;
        }

        /** Whether world block (x, y, z) is inside the box. */
        public boolean contains(double x, double y, double z) {
            return x >= minX && y >= minY && z >= minZ && x < minX + nx && y < minY + ny && z < minZ + nz;
        }

        /**
         * The offset, C, at a world point: trilinear over the air cells around it (walls don't count), 0 outside the
         * box or where no air cell is near.
         */
        public double offsetAt(double x, double y, double z) {
            double px = x - minX - 0.5;
            double py = y - minY - 0.5;
            double pz = z - minZ - 0.5;
            int x0 = (int) Math.floor(px);
            int y0 = (int) Math.floor(py);
            int z0 = (int) Math.floor(pz);
            double fx = px - x0;
            double fy = py - y0;
            double fz = pz - z0;
            double sum = 0;
            double weight = 0;
            for (int c = 0; c < 8; c++) {
                int cx = x0 + (c & 1);
                int cy = y0 + ((c >> 1) & 1);
                int cz = z0 + ((c >> 2) & 1);
                if (cx < 0 || cy < 0 || cz < 0 || cx >= nx || cy >= ny || cz >= nz) {
                    continue;
                }
                double w = ((c & 1) == 0 ? 1 - fx : fx) * (((c >> 1) & 1) == 0 ? 1 - fy : fy)
                        * (((c >> 2) & 1) == 0 ? 1 - fz : fz);
                int i = index(cx, cy, cz);
                byte k = kinds[i];
                if (w > 0 && (k == AirGrid.AIR || k == AirGrid.EXPOSED)) {
                    sum += w * offsets[i];
                    weight += w;
                }
            }
            return weight > 1e-9 ? sum / weight : 0;
        }

        /** The largest offset magnitude in the field. */
        public double maxMagnitude() {
            double m = 0;
            for (float v : offsets) {
                m = Math.max(m, Math.abs(v));
            }
            return m;
        }
    }

    final int id;
    /** World box {minX, minY, minZ, maxX, maxY, maxZ}, inclusive; the outermost layer is boundary. */
    final int[] box;
    final int nx, ny, nz;
    final AirGrid grid;

    // ---- main thread ----
    /** Whether it holds natural sources only (dropped when nobody is near; never saved). */
    boolean naturalOnly;
    /** Whether the blocks inside changed since the geometry was last taken. */
    boolean geometryDirty = true;
    /** The latest geometry taken (main thread's copy, for placing sources). */
    Geometry geometry;
    /** Geometry not yet handed to a job. */
    Geometry pendingGeometry;
    /** Offsets to start from, not yet handed to a job (a new region: from the regions it replaces, or saved). */
    float[] pendingInitial;
    /** Whether the next job should first run the field to its steady state (natural regions starting up). */
    boolean pendingSettle;
    List<AirGrid.Source> sources = List.of();
    double windX, windZ;
    /** Game tick of the last step started; Long.MIN_VALUE for none yet. */
    long lastStep = Long.MIN_VALUE;
    boolean inFlight;
    boolean removed;
    /** Whether a player or ship was near at the last plan. */
    boolean near;
    /** The ship whose plot it is in (8f-5), or null for the world. Main thread. */
    dev.ryanhcode.sable.sublevel.SubLevel ship;
    /** Whether its ship sits under a world roof (a hangar), so it has no open-sky cells. */
    boolean roofed;
    long lastNanos;

    /** The latest finished field (null before the first). */
    volatile Published published;

    HeatRegion(int id, int[] box, AirParams params) {
        this.id = id;
        this.box = box.clone();
        this.nx = box[3] - box[0] + 1;
        this.ny = box[4] - box[1] + 1;
        this.nz = box[5] - box[2] + 1;
        this.grid = new AirGrid(nx, ny, nz, params);
    }

    public int id() {
        return id;
    }

    public int[] box() {
        return box.clone();
    }

    public boolean naturalOnly() {
        return naturalOnly;
    }

    public int sourceCount() {
        return sources.size();
    }

    public long lastStep() {
        return lastStep;
    }

    public long lastNanos() {
        return lastNanos;
    }

    public Published published() {
        return published;
    }

    /** The ship it is on (8f-5), or null for the world. */
    public dev.ryanhcode.sable.sublevel.SubLevel ship() {
        return ship;
    }

    int index(int x, int y, int z) {
        return (z * ny + y) * nx + x;
    }

    /** Whether world block (x, y, z) is in the box. */
    public boolean contains(int x, int y, int z) {
        return x >= box[0] && y >= box[1] && z >= box[2] && x <= box[3] && y <= box[4] && z <= box[5];
    }

    /** Whether the box overlaps {@code other} (same layout). */
    boolean overlaps(int[] other) {
        return box[0] <= other[3] && other[0] <= box[3] && box[1] <= other[4] && other[1] <= box[4]
                && box[2] <= other[5] && other[2] <= box[5];
    }

    boolean sameBox(int[] other) {
        return java.util.Arrays.equals(box, other);
    }
}
