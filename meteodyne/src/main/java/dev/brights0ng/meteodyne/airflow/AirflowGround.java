// Written by Claude (GreedyFluids M7e: the smoke's p99 fix).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;

/**
 * The world generator's terrain height for the tiles' ground samples (M7e, the smoke's p99 fix; Bright,
 * 2026-11-15). The cover over a point is the lower of the heightmap and the generator's own terrain, as
 * {@code UndergroundProbe} takes it, and the generator's height is a noise evaluation: about 1.5 ms a column, which
 * in the smoke held a new tile for 12 s at 1.7 ms a server tick. Here it is worked out
 * <ul>
 *   <li>only at chunk corners, every {@link #SPACING} blocks, and interpolated between them (cover eases in over 20
 *       blocks, so a 16-block lattice loses little);</li>
 *   <li>off the server thread (vanilla evaluates the same noise on its world-generation workers);</li>
 *   <li>once per corner, shared by the tiles around it; at most {@link #CACHE} corners are kept.</li>
 * </ul>
 * Pure Java: the height function and the executor are given.
 */
final class AirflowGround {

    /** Corner spacing, blocks: the chunk grid. */
    static final int SPACING = 16;
    /** The most corners kept; past it the cache starts again. */
    static final int CACHE = 1 << 16;

    private final IntBinaryOperator height;
    private final Executor executor;
    private final int fallback;
    private final Consumer<Throwable> onFailure;
    private final int cache;
    private final ConcurrentHashMap<Long, Integer> heights = new ConcurrentHashMap<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean warned;

    /**
     * @param height    the generator's height at (x, z) world blocks; must be safe off the server thread
     * @param fallback  the height used where {@code height} fails (the top of the world: the heightmap alone counts)
     * @param onFailure hears the first failure
     */
    AirflowGround(IntBinaryOperator height, Executor executor, int fallback, Consumer<Throwable> onFailure) {
        this(height, executor, fallback, onFailure, CACHE);
    }

    AirflowGround(IntBinaryOperator height, Executor executor, int fallback, Consumer<Throwable> onFailure, int cache) {
        this.height = height;
        this.executor = executor;
        this.fallback = fallback;
        this.onFailure = onFailure;
        this.cache = cache;
    }

    /**
     * The heights at corners (cx0 + i, cz0 + k) for i &lt; ncx, k &lt; ncz (corner units: blocks / {@link #SPACING}),
     * i fastest; or null while some are still being worked out. The missing ones are asked for.
     */
    int[] corners(int cx0, int cz0, int ncx, int ncz) {
        int[] out = new int[ncx * ncz];
        boolean complete = true;
        List<Long> missing = null;
        for (int k = 0; k < ncz; k++) {
            for (int i = 0; i < ncx; i++) {
                long key = key(cx0 + i, cz0 + k);
                Integer h = heights.get(key);
                if (h != null) {
                    out[k * ncx + i] = h;
                    continue;
                }
                complete = false;
                if (pending.add(key)) {
                    if (missing == null) {
                        missing = new ArrayList<>();
                    }
                    missing.add(key);
                }
            }
        }
        if (missing != null) {
            List<Long> keys = missing;
            try {
                executor.execute(() -> work(keys));
            } catch (RejectedExecutionException e) {
                work(keys); // the workers are shutting down: here, once
            }
        }
        return complete ? out : null;
    }

    /** Corners known now (tests and status). */
    int known() {
        return heights.size();
    }

    private void work(List<Long> keys) {
        if (heights.size() + keys.size() > cache) {
            heights.clear();
        }
        for (long key : keys) {
            int h;
            try {
                h = height.applyAsInt((int) (key >> 32) * SPACING, (int) key * SPACING);
            } catch (RuntimeException | LinkageError e) {
                h = fallback;
                if (!warned) {
                    warned = true;
                    onFailure.accept(e);
                }
            }
            heights.put(key, h);
            pending.remove(key);
        }
    }

    /**
     * The height at (x, z) world blocks, bilinear between the corners around it; {@code corners} as
     * {@link #corners} gives them from corner (cx0, cz0), {@code ncx} wide. (x, z) must lie inside them.
     */
    static double at(int[] corners, int cx0, int cz0, int ncx, int x, int z) {
        int cx = Math.floorDiv(x, SPACING), cz = Math.floorDiv(z, SPACING);
        double tx = (x - cx * SPACING) / (double) SPACING, tz = (z - cz * SPACING) / (double) SPACING;
        int i = cx - cx0, k = cz - cz0;
        double a = corners[k * ncx + i], b = corners[k * ncx + i + 1];
        double c = corners[(k + 1) * ncx + i], d = corners[(k + 1) * ncx + i + 1];
        double near = a + (b - a) * tx, far = c + (d - c) * tx;
        return near + (far - near) * tz;
    }

    /** The first corner at or below {@code block}, corner units. */
    static int corner(int block) {
        return Math.floorDiv(block, SPACING);
    }

    /** Corners needed along one axis for blocks {@code from} to {@code to} inclusive. */
    static int span(int from, int to) {
        return corner(to) - corner(from) + 2;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
