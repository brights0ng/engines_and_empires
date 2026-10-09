package dev.brights0ng.enginesandempires.weather.ships;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.util.LevelAccelerator;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Ships (Sable sub-levels) as cover from the sky (phase 5d of the weather backbone, 2026-10-08). A ship's blocks live in
 * Sable's plot grid, far from where the ship is seen, so the world's heightmaps never see them: this finds them.
 *
 * <ul>
 *   <li>{@link #top}: the highest top surface of any ship block over a world column (straight up in the world, through
 *       each ship's own tilt), the way Sable's own fix for vanilla's rain renderer looks (marching down each ship that
 *       crosses the column, in the ship's frame). "Blocks" are what stops rain: anything that blocks motion, or
 *       fluid, as the world's {@code MOTION_BLOCKING} heightmap counts them.</li>
 *   <li>{@link Area}: the ships over a patch of world, found once, with a per-column cache (the rain renderer, the
 *       ground's surface visits).</li>
 *   <li>{@link #inPlot} / {@link #worldCentre}: whether a block or chunk is a ship's (in the plot grid), and where it is
 *       in the world.</li>
 * </ul>
 * Works on both sides.
 */
public final class ShipCover {

    /** None: nothing over the column. */
    public static final double NONE = Double.NEGATIVE_INFINITY;
    /** How far apart (world blocks) a column is sampled down a ship. */
    private static final double STEP = 0.5;
    private static final int REFINE = 4;

    /** Whether chunk {@code cp} of {@code level} is in Sable's plot grid (a ship's, not the world's). */
    public static boolean inPlot(Level level, ChunkPos cp) {
        SubLevelContainer c = SubLevelContainer.getContainer(level);
        return c != null && c.inBounds(cp);
    }

    /** Whether {@code pos} is in Sable's plot grid. */
    public static boolean inPlot(Level level, BlockPos pos) {
        SubLevelContainer c = SubLevelContainer.getContainer(level);
        return c != null && c.inBounds(pos);
    }

    /** The ship whose plot holds {@code pos}, or null. */
    public static SubLevel shipAt(Level level, BlockPos pos) {
        return Sable.HELPER.getContaining(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Where the centre of plot block {@code pos} is in the world, or null if it isn't a ship's. */
    public static Vec3 worldCentre(Level level, BlockPos pos) {
        if (!inPlot(level, pos)) {
            return null;
        }
        SubLevel ship = shipAt(level, pos);
        return ship == null ? null : ship.logicalPose().transformPosition(Vec3.atCenterOf(pos));
    }

    /**
     * The highest top surface (world y) of ship blocks over world column ({@code x}, {@code z}) that is above
     * {@code above}, or {@link #NONE}.
     */
    public static double top(Level level, double x, double z, double above) {
        BoundingBox3dc column = new BoundingBox3d(x - 0.01, above, z - 0.01, x + 0.01, level.getMaxBuildHeight(), z + 0.01);
        LevelAccelerator acc = null;
        double best = NONE;
        for (SubLevel ship : Sable.HELPER.getAllIntersecting(level, column)) {
            if (ship.isRemoved()) {
                continue;
            }
            if (acc == null) {
                acc = new LevelAccelerator(level);
            }
            best = Math.max(best, topIn(ship, acc, x, z, above));
        }
        return best;
    }

    /** Whether any ship block is over (x, y, z). */
    public static boolean covered(Level level, double x, double y, double z) {
        return top(level, x, z, y) > y;
    }

    /**
     * The plot block (in a ship's own grid) at world point (x, y, z), or null if no ship's bounds hold it. Used to put
     * a lightning strike's fire on the deck it hit (weather phase 6b).
     */
    public static BlockPos plotAt(Level level, double x, double y, double z) {
        BoundingBox3dc point = new BoundingBox3d(x - 0.01, y - 0.01, z - 0.01, x + 0.01, y + 0.01, z + 0.01);
        for (SubLevel ship : Sable.HELPER.getAllIntersecting(level, point)) {
            if (ship.isRemoved()) {
                continue;
            }
            Vector3d p = ship.logicalPose().transformPositionInverse(new Vector3d(x, y, z));
            return BlockPos.containing(p.x, p.y, p.z);
        }
        return null;
    }

    /** The ships whose bounds meet a world box. */
    public static List<SubLevel> shipsIn(Level level, double minX, double minY, double minZ, double maxX, double maxY,
                                         double maxZ) {
        List<SubLevel> out = new ArrayList<>();
        for (SubLevel ship : Sable.HELPER.getAllIntersecting(level,
                new BoundingBox3d(minX, minY, minZ, maxX, maxY, maxZ))) {
            if (!ship.isRemoved()) {
                out.add(ship);
            }
        }
        return out;
    }

    /**
     * Where a strike from (x, z) lands on {@code ship}: the top of its blocks over the point of its bounds nearest
     * (x, z), or over its middle if that column misses its blocks; null if neither meets a block.
     */
    public static Vec3 strikePoint(Level level, SubLevel ship, double x, double z) {
        BoundingBox3dc b = ship.boundingBox();
        LevelAccelerator acc = new LevelAccelerator(level);
        double px = Math.max(b.minX() + 0.25, Math.min(b.maxX() - 0.25, x));
        double pz = Math.max(b.minZ() + 0.25, Math.min(b.maxZ() - 0.25, z));
        double top = topIn(ship, acc, px, pz, b.minY() - 1);
        if (top == NONE) {
            px = (b.minX() + b.maxX()) / 2;
            pz = (b.minZ() + b.maxZ()) / 2;
            top = topIn(ship, acc, px, pz, b.minY() - 1);
        }
        return top == NONE ? null : new Vec3(px, top, pz);
    }

    /** The ships over a rectangle of world columns, for many lookups (each column's top cached). */
    public static Area area(Level level, double minX, double minZ, double maxX, double maxZ) {
        BoundingBox3dc box = new BoundingBox3d(minX, level.getMinBuildHeight(), minZ, maxX, level.getMaxBuildHeight(),
                maxZ);
        List<SubLevel> ships = new ArrayList<>();
        for (SubLevel ship : Sable.HELPER.getAllIntersecting(level, box)) {
            if (!ship.isRemoved()) {
                ships.add(ship);
            }
        }
        return new Area(level, ships);
    }

    /** The ships over a patch of world. */
    public static final class Area {

        private final Level level;
        private final List<SubLevel> ships;
        private final Long2DoubleOpenHashMap tops = new Long2DoubleOpenHashMap();
        private LevelAccelerator acc;

        Area(Level level, List<SubLevel> ships) {
            this.level = level;
            this.ships = ships;
        }

        public boolean isEmpty() {
            return ships.isEmpty();
        }

        /** The ships over the patch. */
        public List<SubLevel> ships() {
            return ships;
        }

        /** The highest ship top over block column (x, z), at any height, or {@link #NONE}. Cached. */
        public double top(int x, int z) {
            if (ships.isEmpty()) {
                return NONE;
            }
            long key = ChunkPos.asLong(x, z);
            if (tops.containsKey(key)) {
                return tops.get(key);
            }
            double t = topAbove(x + 0.5, z + 0.5, level.getMinBuildHeight());
            tops.put(key, t);
            return t;
        }

        /** The highest ship top over (x, z) above {@code above}, or {@link #NONE} (not cached). */
        public double topAbove(double x, double z, double above) {
            if (ships.isEmpty()) {
                return NONE;
            }
            if (acc == null) {
                acc = new LevelAccelerator(level);
            }
            double best = NONE;
            for (SubLevel ship : ships) {
                best = Math.max(best, topIn(ship, acc, x, z, above));
            }
            return best;
        }
    }

    /**
     * Down world column (x, z) through {@code ship}, from the top of its bounds to {@code above}: the world y of the top
     * of the first of its blocks met, or {@link #NONE}.
     */
    static double topIn(SubLevel ship, LevelAccelerator acc, double x, double z, double above) {
        BoundingBox3dc b = ship.boundingBox();
        if (x < b.minX() || x > b.maxX() || z < b.minZ() || z > b.maxZ()) {
            return NONE;
        }
        double hi = b.maxY();
        double lo = Math.max(above, b.minY());
        if (hi <= lo) {
            return NONE;
        }
        Pose3dc pose = ship.logicalPose();
        Vector3d p = new Vector3d();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double clear = hi;
        for (double y = hi; y > lo; ) {
            y = Math.max(lo, y - STEP);
            if (stops(acc, pose, x, y, z, p, m)) {
                // Between y (in a block) and clear (not): find the top face.
                double in = y;
                double out = clear;
                for (int i = 0; i < REFINE; i++) {
                    double mid = (in + out) / 2;
                    if (stops(acc, pose, x, mid, z, p, m)) {
                        in = mid;
                    } else {
                        out = mid;
                    }
                }
                return out;
            }
            clear = y;
        }
        return NONE;
    }

    private static boolean stops(LevelAccelerator acc, Pose3dc pose, double x, double y, double z, Vector3d p,
                                 BlockPos.MutableBlockPos m) {
        pose.transformPositionInverse(p.set(x, y, z));
        BlockState s = acc.getBlockState(m.set(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z)));
        return s.blocksMotion() || !s.getFluidState().isEmpty();
    }

    private ShipCover() {
    }
}
