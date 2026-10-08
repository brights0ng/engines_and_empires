package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudMeshes.Planned;

/**
 * When cloud formations are rebuilt and in what order, and which sections can be left out (Bright, 2026-10-08). Pure
 * Java, tested; {@link CloudMeshes} applies it.
 *
 * <h2>Refresh targets</h2>
 * Each formation aims to be drawn from data at most {@link #targetSeconds} old: {@link #NEAR_SECONDS} for clouds near
 * the camera and for any cloud being born or dying, rising to {@link #FAR_SECONDS} for steady clouds far away. A
 * formation too big for that (its last generation cost more than {@link #MAX_SHARE} of the budget over the target)
 * gets a longer target, so no single storm can take the whole budget. A generation starts early enough to finish on
 * time, judged by how long the last one took.
 *
 * <h2>Order</h2>
 * Rebuilds go by {@link #score}: how far past its target a formation is, over the build time it still needs.
 * A cheap cloud that is due goes ahead of a storm that needs seconds more work, but a storm waiting long enough comes
 * first in the end, and the less it has left the sooner. (Before, everything older than 4 rebuild gaps went first,
 * oldest first: with a busy sky every formation was that old, and a cumulus overhead waited behind a stratocumulus
 * deck 8 km away, 11 s stale.)
 *
 * <h2>Remembered empty sections</h2>
 * Most of a big layer's or storm's box is empty air that still passes the coarse cull (65-94% of their builds came
 * out empty). A section that came out empty is left out of the next generations ({@link #skip}) and rechecked
 * after {@link #EMPTY_NEAR_GENS} generations if it touches a section with cloud (where cloud grows in), otherwise
 * after {@link #EMPTY_FAR_GENS} to {@link #EMPTY_FAR_GENS} + {@link #EMPTY_FAR_SPREAD} - 1 (spread out so they don't
 * all come back in one generation), and always within {@link #EMPTY_MAX_TICKS}. So cloud growing into empty sky can
 * show a generation or few late, never more than a minute.
 */
final class RebuildSchedule {

    /** The refresh target near the camera, and for clouds being born or dying anywhere, seconds. */
    static final double NEAR_SECONDS = 2;
    /** The refresh target for steady clouds far away, seconds. */
    static final double FAR_SECONDS = 30;
    /** Up to this far (blocks, to the formation's box) a cloud counts as near. */
    static final double NEAR_DISTANCE = 512;
    /** From this far it counts as far; in between the target rises smoothly (geometrically). */
    static final double FAR_DISTANCE = 2048;
    /** The most of the rebuild budget one formation's refreshes may take. */
    static final double MAX_SHARE = 0.5;
    /** The least build time a formation is taken to still need, seconds (keeps {@link #score} finite). */
    static final double MIN_COST_SECONDS = 0.002;

    /** Generations an empty section touching cloud is left out for. */
    static final int EMPTY_NEAR_GENS = 2;
    /** Generations other empty sections are left out for, at least; plus up to {@link #EMPTY_FAR_SPREAD} - 1. */
    static final int EMPTY_FAR_GENS = 4;
    static final int EMPTY_FAR_SPREAD = 5;
    /** The longest an empty section goes unchecked, ticks. */
    static final long EMPTY_MAX_TICKS = 1200;

    /** The refresh target for a steady cloud this far away (blocks), seconds. */
    static double distanceSeconds(double distance) {
        if (distance <= NEAR_DISTANCE) {
            return NEAR_SECONDS;
        }
        if (distance >= FAR_DISTANCE) {
            return FAR_SECONDS;
        }
        double f = Math.log(distance / NEAR_DISTANCE) / Math.log(FAR_DISTANCE / NEAR_DISTANCE);
        return NEAR_SECONDS * Math.pow(FAR_SECONDS / NEAR_SECONDS, f);
    }

    /**
     * A formation's refresh target, seconds: by distance (blocks), or {@link #NEAR_SECONDS} if any of its clouds is
     * being born or dying; never less than its last generation's build time over {@link #MAX_SHARE} of the budget
     * (cores), nor than the configured shortest gap.
     */
    static double targetSeconds(double distance, boolean changing, double genCostSeconds, double budgetCores,
                                double minGapSeconds) {
        double t = changing ? NEAR_SECONDS : distanceSeconds(distance);
        if (budgetCores > 0 && genCostSeconds > 0) {
            t = Math.max(t, genCostSeconds / (budgetCores * MAX_SHARE));
        }
        return Math.max(t, minGapSeconds);
    }

    /**
     * The tick a formation's next generation should start: early enough that, taking as long as the last one, it is
     * shown before the drawn one is {@code targetTicks} old; never sooner than {@code minGap} after the last start.
     */
    static long dueTick(long liveStart, long lastGenStart, long targetTicks, long expectedTicks, long minGap) {
        long byTarget = liveStart == Long.MIN_VALUE ? Long.MIN_VALUE : liveStart + targetTicks - expectedTicks;
        long byGap = lastGenStart == Long.MIN_VALUE ? Long.MIN_VALUE : lastGenStart + minGap;
        return Math.max(byTarget, byGap);
    }

    /**
     * How urgently a formation's rebuild should go (higher first): its drawn meshes' age over its target, over the
     * build time it still needs (seconds).
     */
    static double score(double ageSeconds, double targetSeconds, double remainingCostSeconds) {
        return Math.max(0, ageSeconds) / targetSeconds / Math.max(remainingCostSeconds, MIN_COST_SECONDS);
    }

    /** Distance (blocks) from (x, y, z) to box {min x, max x, min y, max y, min z, max z}; 0 inside. */
    static double boxDistance(double[] b, double x, double y, double z) {
        double dx = x < b[0] ? b[0] - x : x > b[1] ? x - b[1] : 0;
        double dy = y < b[2] ? b[2] - y : y > b[3] ? y - b[3] : 0;
        double dz = z < b[4] ? b[4] - z : z > b[5] ? z - b[5] : 0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * A section that came out empty: at voxel size {@code voxel}, checked by generation {@code checkedGen} (started at
     * tick {@code checkedTick}), left out until generation {@code recheckGen}; {@code nearMesh}: it touches a section
     * with cloud.
     */
    record Empty(int voxel, long checkedGen, long checkedTick, long recheckGen, boolean nearMesh) {
    }

    /** Section {@code key} came out empty in generation {@code gen}. */
    static Empty emptied(long key, int voxel, long gen, long tick, boolean nearMesh) {
        long wait = nearMesh ? EMPTY_NEAR_GENS : EMPTY_FAR_GENS + Math.floorMod(mix(key, gen), EMPTY_FAR_SPREAD);
        return new Empty(voxel, gen, tick, gen + wait, nearMesh);
    }

    /** {@code e} after generation {@code gen} finished: whether it now touches a section with cloud. */
    static Empty touching(Empty e, boolean nearMesh, long gen) {
        if (nearMesh == e.nearMesh()) {
            return e;
        }
        long recheck = nearMesh ? Math.min(e.recheckGen(), Math.max(gen + 1, e.checkedGen() + EMPTY_NEAR_GENS))
                : e.recheckGen();
        return new Empty(e.voxel(), e.checkedGen(), e.checkedTick(), recheck, nearMesh);
    }

    /**
     * Whether generation {@code gen} (starting at {@code tick}) may leave out a section remembered as {@code e}. In a
     * formation being born or dying ({@code changing}) every empty section is rechecked as soon as one touching cloud
     * would be, so a newborn cloud with nothing drawn yet doesn't pop in generations late.
     */
    static boolean skip(Empty e, int voxel, long gen, long tick, boolean changing) {
        if (e == null || e.voxel() != voxel || tick - e.checkedTick() >= EMPTY_MAX_TICKS) {
            return false;
        }
        long recheck = changing ? Math.min(e.recheckGen(), e.checkedGen() + EMPTY_NEAR_GENS) : e.recheckGen();
        return gen < recheck;
    }

    private static long mix(long key, long gen) {
        long z = key * 0x9E3779B97F4A7C15L + gen;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A section's key: its x, y and z section indices, 21 bits each (signed). */
    static long key(int sx, int sy, int sz) {
        return ((long) (sx & 0x1FFFFF) << 42) | ((long) (sy & 0x1FFFFF) << 21) | (sz & 0x1FFFFF);
    }

    static int keyX(long key) {
        return (int) ((key << 1) >> 43);
    }

    static int keyY(long key) {
        return (int) ((key << 22) >> 43);
    }

    static int keyZ(long key) {
        return (int) ((key << 43) >> 43);
    }

    /**
     * The sections a generation builds: every section of the bounds within the draw distance (horizontally), with its
     * voxel size by distance, then evened out so neighbours differ by at most 2x. Nearest first. Camera coordinates
     * are anchor-local x and z, world y. (Moved here from {@link CloudMeshes} so tests can plan without Minecraft.)
     */
    static List<Planned> plan(double[] b, double camX, double camY, double camZ, int sectionSize, int voxel,
                              double drawDistance) {
        Map<Long, Planned> plan = new HashMap<>();
        int x0 = Math.floorDiv((int) Math.floor(b[0]), sectionSize), x1 = Math.floorDiv((int) Math.ceil(b[1]), sectionSize);
        int y0 = Math.floorDiv((int) Math.floor(b[2]), sectionSize), y1 = Math.floorDiv((int) Math.ceil(b[3]), sectionSize);
        int z0 = Math.floorDiv((int) Math.floor(b[4]), sectionSize), z1 = Math.floorDiv((int) Math.ceil(b[5]), sectionSize);
        for (int sx = x0; sx <= x1; sx++) {
            for (int sz = z0; sz <= z1; sz++) {
                double hx = axisGap(camX, sx, sectionSize), hz = axisGap(camZ, sz, sectionSize);
                double horizontal = Math.sqrt(hx * hx + hz * hz);
                if (horizontal > drawDistance) {
                    continue;
                }
                for (int sy = y0; sy <= y1; sy++) {
                    double hy = axisGap(camY, sy, sectionSize);
                    double dist = Math.sqrt(horizontal * horizontal + hy * hy);
                    int size = Math.min(CloudTuning.voxelSizeAt(dist, voxel), sectionSize);
                    plan.put(key(sx, sy, sz), new Planned(sx, sy, sz, size, dist));
                }
            }
        }
        // 2:1 balance: refine any section more than twice as coarse as a neighbour, until none is.
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        boolean changed = true;
        for (int pass = 0; changed && pass < 8; pass++) {
            changed = false;
            for (Map.Entry<Long, Planned> e : plan.entrySet()) {
                Planned p = e.getValue();
                int limit = p.voxel();
                for (int[] d : dirs) {
                    Planned n = plan.get(key(p.sx() + d[0], p.sy() + d[1], p.sz() + d[2]));
                    if (n != null) {
                        limit = Math.min(limit, n.voxel() * 2);
                    }
                }
                if (limit < p.voxel()) {
                    e.setValue(new Planned(p.sx(), p.sy(), p.sz(), limit, p.distance()));
                    changed = true;
                }
            }
        }
        List<Planned> out = new ArrayList<>(plan.values());
        out.sort(Comparator.comparingDouble(Planned::distance));
        return out;
    }

    /** Blocks from {@code c} to section {@code index}'s span along one axis (0 inside). */
    private static double axisGap(double c, int index, int size) {
        double lo = (double) index * size;
        double hi = lo + size;
        return c < lo ? lo - c : c > hi ? c - hi : 0;
    }

    private RebuildSchedule() {
    }
}
