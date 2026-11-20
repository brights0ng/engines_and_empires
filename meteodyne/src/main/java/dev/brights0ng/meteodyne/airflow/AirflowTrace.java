// Written by Claude (GreedyFluids M8b b4: G2, the enclosed air a player is in).
package dev.brights0ng.meteodyne.airflow;

import java.util.BitSet;

import dev.brights0ng.greedyfluids.grid.CellGrid3D.Side;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * G2 (M8b b4; Bright, 2026-11-17): the enclosed air a player is in, followed outward from their cell up to a walking
 * limit, and the tile borders it crosses. Secondaries are the tiles this air reaches across a border with at least N
 * cell pairs ({@link AirflowNeighbourTiles}); a player outdoors has none.
 * <ul>
 *   <li><b>What the trace walks:</b> cells the host calls {@link #OPEN}, which Meteodyne makes air or an open door
 *       below its column's open height. Solids and shut doors stop it (Bright: no need to simulate rooms players
 *       aren't in), and so does open air under the sky.</li>
 *   <li><b>Walking limit:</b> a breadth-first walk of at most {@code reach} steps (6-connected), so a cave network is
 *       followed only near the player. It never leaves the 3×3×3 block of tiles around the player's.</li>
 *   <li><b>Crossings:</b> each pair of walked-to cells across a tile border, counted once, by the face it crosses
 *       ({@link AirflowNeighbourTiles#faceKey}, relative to the player's tile).</li>
 *   <li><b>Resumable:</b> {@link #advance} stops at a deadline and carries on at the next call, so the server thread
 *       spends a bounded time per tick on it.</li>
 *   <li><b>Unknown cells</b> (a section not read, a chunk not loaded) stop the walk there and mark it incomplete; the
 *       host asks for what was missing and traces again.</li>
 * </ul>
 * Pure Java.
 */
public final class AirflowTrace {

    /** What the host says a cell is. */
    public static final int UNKNOWN = -1, BLOCKED = 0, OPEN = 1;

    /** The cells the trace may walk, world blocks. */
    @FunctionalInterface
    public interface Space {
        /** {@link #OPEN}, {@link #BLOCKED} or {@link #UNKNOWN} for the cell at (x, y, z). */
        int at(int x, int y, int z);
    }

    private final int sx, sy, sz, reach, span;
    private final int hx0, hy0, hz0, tx, ty, tz;
    private final BitSet seen;
    private IntArrayList frontier = new IntArrayList(), next = new IntArrayList();
    private int pos, level;
    private final int[] crossings = new int[27 * 3];
    private boolean complete = true, done, enclosed;
    private int walked;

    /**
     * A trace from cell (x, y, z), world blocks, up to {@code reach} steps, in a world of tiles of {@code size} with
     * the player's tile at {@code home} (origins, world blocks). The start must be {@link #OPEN} in {@code space} for
     * the trace to walk; otherwise it is done at once and not enclosed.
     */
    public AirflowTrace(int x, int y, int z, int reach, int[] home, int[] size, Space space) {
        this.sx = x;
        this.sy = y;
        this.sz = z;
        this.reach = reach;
        this.span = 2 * reach + 1;
        this.hx0 = home[0];
        this.hy0 = home[1];
        this.hz0 = home[2];
        this.tx = size[0];
        this.ty = size[1];
        this.tz = size[2];
        this.seen = new BitSet(span * span * span);
        int start = space.at(x, y, z);
        if (start != OPEN) {
            done = true;
            complete = start != UNKNOWN;
            return;
        }
        enclosed = true;
        int i = index(0, 0, 0);
        seen.set(i);
        frontier.add(i);
        walked = 1;
    }

    /** Walks until done or {@code deadline} ({@link System#nanoTime}); returns whether done. */
    public boolean advance(Space space, long deadline) {
        int checked = 0;
        while (!done) {
            if (pos == frontier.size()) {
                IntArrayList t = frontier;
                frontier = next;
                next = t;
                next.clear();
                pos = 0;
                level++;
                if (frontier.isEmpty()) {
                    done = true;
                    break;
                }
                continue;
            }
            int p = frontier.getInt(pos++);
            int pdx = p % span - reach, pdz = p / span % span - reach, pdy = p / (span * span) - reach;
            int px = sx + pdx, py = sy + pdy, pz = sz + pdz;
            int ptx = Math.floorDiv(px - hx0, tx), pty = Math.floorDiv(py - hy0, ty), ptz = Math.floorDiv(pz - hz0, tz);
            for (int d = 0; d < 6; d++) {
                int ax = d >> 1, step = (d & 1) == 0 ? -1 : 1;
                int qdx = pdx + (ax == 0 ? step : 0), qdy = pdy + (ax == 1 ? step : 0), qdz = pdz + (ax == 2 ? step : 0);
                if (Math.abs(qdx) > reach || Math.abs(qdy) > reach || Math.abs(qdz) > reach) {
                    continue;
                }
                int qx = sx + qdx, qy = sy + qdy, qz = sz + qdz;
                int qtx = Math.floorDiv(qx - hx0, tx), qty = Math.floorDiv(qy - hy0, ty), qtz = Math.floorDiv(qz - hz0, tz);
                if (Math.abs(qtx) > 1 || Math.abs(qty) > 1 || Math.abs(qtz) > 1) {
                    continue; // outside the block
                }
                int a = space.at(qx, qy, qz);
                if (a == UNKNOWN) {
                    complete = false;
                    continue;
                }
                if (a != OPEN) {
                    continue;
                }
                boolean across = qtx != ptx || qty != pty || qtz != ptz;
                if (across && step > 0) { // each pair once, from its lower cell
                    crossings[AirflowNeighbourTiles.faceKey(ptx, pty, ptz,
                            ax == 0 ? Side.EAST : ax == 1 ? Side.UP : Side.SOUTH)]++;
                }
                int q = index(qdx, qdy, qdz);
                if (level < reach && !seen.get(q)) {
                    seen.set(q);
                    next.add(q);
                    walked++;
                }
            }
            if ((++checked & 255) == 0 && System.nanoTime() >= deadline) {
                return false;
            }
        }
        return true;
    }

    /** Whether the walk has ended. */
    public boolean done() {
        return done;
    }

    /** Whether the start was enclosed air (a player outdoors has no secondaries). */
    public boolean enclosed() {
        return enclosed;
    }

    /** Whether every cell the walk met was known. */
    public boolean complete() {
        return complete;
    }

    /** Cells walked so far, the start included. */
    public int walked() {
        return walked;
    }

    /** Pairs crossing the face {@code faceKey} (relative to the player's tile), so far. */
    public int crossings(int faceKey) {
        return crossings[faceKey];
    }

    /** The crossings as the reach's face counts. */
    AirflowNeighbourTiles.Faces faces() {
        return (dx, dy, dz, side) -> crossings[AirflowNeighbourTiles.faceKey(dx, dy, dz, side)];
    }

    private int index(int dx, int dy, int dz) {
        return ((dy + reach) * span + (dz + reach)) * span + (dx + reach);
    }
}
