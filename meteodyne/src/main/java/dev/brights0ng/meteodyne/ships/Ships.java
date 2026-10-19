// Extended by ChatGPT: instrument cover rays can exclude their own plot block.
package dev.brights0ng.meteodyne.ships;

import java.util.List;
import java.util.function.ToIntFunction;

import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What the weather asks about ships (Sable sub-levels): where they cover the sky, where a block of theirs is in the
 * world, what a lightning bolt can hit. Sable is optional to Meteodyne (split step 2c), so nothing outside the
 * {@code meteodyne.sable} package names its classes: this interface uses vanilla types only, and {@link #NONE} (no
 * ships anywhere) answers until {@code MeteodyneSable} registers the Sable-backed one, which it does only when Sable
 * is loaded. With Sable loaded but no ship around, both answer the same.
 */
public interface Ships {

    /** No ship's top over a column. */
    double NONE_TOP = Double.NEGATIVE_INFINITY;

    /** No Sable: no ships, no plot grid; nothing covers, nothing is aboard. */
    Ships NONE = new Ships() {
    };

    /** The registered answer, or {@link #NONE}. */
    static Ships get() {
        return Holder.ships;
    }

    /** Sets the answer (Meteodyne's setup, when Sable is loaded). */
    static void use(Ships ships) {
        Holder.ships = ships;
    }

    /** Whether chunk {@code cp} is in Sable's plot grid (a ship's, not the world's). */
    default boolean inPlot(Level level, ChunkPos cp) {
        return false;
    }

    /** Whether {@code pos} is in Sable's plot grid. */
    default boolean inPlot(Level level, BlockPos pos) {
        return false;
    }

    /** Where the centre of plot block {@code pos} is in the world, or null if it isn't a ship's. */
    default Vec3 worldCentre(Level level, BlockPos pos) {
        return null;
    }

    /** Whether any ship block is over (x, y, z). */
    default boolean covered(Level level, double x, double y, double z) {
        return false;
    }

    /** The point in a ship's own grid at world point (x, y, z), or null if no ship's bounds hold it. */
    default Vec3 plotPoint(Level level, double x, double y, double z) {
        return null;
    }

    /** The plot block at world point (x, y, z), or null if no ship's bounds hold it. */
    default BlockPos plotAt(Level level, double x, double y, double z) {
        return null;
    }

    /** Whether any ship's bounds come within {@code radius} blocks (a cube) of (x, y, z). */
    default boolean near(Level level, double x, double y, double z, double radius) {
        return false;
    }

    /**
     * The worst of {@code classify} (0 nothing, 1 partly, 2 stops the ray) over the ship blocks met along the world ray
     * from (x, y, z) in the unit direction (dx, dy, dz), from {@code from} to {@code reach} blocks out; 0 for none.
     */
    default int rayObstruction(Level level, double x, double y, double z, double dx, double dy, double dz,
                               double from, double reach, ToIntFunction<BlockState> classify) {
        return 0;
    }

    /** Instrument variant, excluding only the named plot block; ordinary ray callers retain their existing behavior. */
    default int rayObstruction(Level level, double x, double y, double z, double dx, double dy, double dz,
                               double from, double reach, ToIntFunction<BlockState> classify, BlockPos ignored) {
        return rayObstruction(level, x, y, z, dx, dy, dz, from, reach, classify);
    }

    /** The ships over a rectangle of world columns, for many lookups. */
    default Cover area(Level level, double minX, double minZ, double maxX, double maxZ) {
        return Cover.EMPTY;
    }

    /**
     * For each ship whose bounds meet the world box, in turn: where a strike from (x, z) lands on it (the top of its
     * blocks over the point of its bounds nearest (x, z), or over its middle), skipping ships it would miss.
     */
    default List<Vec3> strikePointsIn(Level level, double minX, double minY, double minZ, double maxX, double maxY,
                                      double maxZ, double x, double z) {
        return List.of();
    }

    /**
     * Where an entity riding along on a ship stands in that ship's own grid, or null when it isn't on one (the glaze
     * under its feet is in the ship's plot).
     */
    default Vec3 inShipFrame(Entity entity) {
        return null;
    }

    /**
     * The pose, as rendered this frame, of the ship whose plot holds chunk ({@code cx}, {@code cz}), or null if no ship
     * holds it. Client only.
     */
    default Pose renderPose(Level level, int cx, int cz) {
        return null;
    }

    /** The ships over a patch of world. */
    interface Cover {

        /** No ships over the patch. */
        Cover EMPTY = new Cover() {
        };

        default boolean isEmpty() {
            return true;
        }

        /** The highest ship top over block column (x, z), at any height, or {@link #NONE_TOP}. */
        default double top(int x, int z) {
            return NONE_TOP;
        }

        /** The highest ship top over (x, z) above {@code above}, or {@link #NONE_TOP}. */
        default double topAbove(double x, double z, double above) {
            return NONE_TOP;
        }

        /** Each ship over the patch, in turn: where a strike from (x, z) lands on it, skipping ships it would miss. */
        default List<Vec3> strikePoints(double x, double z) {
            return List.of();
        }
    }

    /** A ship's pose: its own grid to the world. */
    interface Pose {

        /** A point of the ship's grid, moved into the world, in place. */
        Vector3d transformPosition(Vector3d point);

        /** A direction in the ship's frame, turned into the world's, in place. */
        Vector3d transformNormal(Vector3d direction);
    }

    /** Where the registered answer is kept. */
    final class Holder {
        private static volatile Ships ships = NONE;

        private Holder() {
        }
    }
}
