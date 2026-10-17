package dev.brights0ng.meteodyne.heat.world;

import java.util.List;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the heat simulation asks about ships (8f-5: a ship's rooms warm and cool like the world's, in its own grid).
 * Sable is optional to Meteodyne (split step 2c): a ship is an opaque handle here (Sable's sub-level, compared by
 * identity), and {@link #NONE} (no ships) answers until {@code MeteodyneSable} registers the Sable-backed one, only
 * when Sable is loaded.
 */
public interface HeatShips {

    /** No Sable: no ships. */
    HeatShips NONE = new HeatShips() {
    };

    /** The registered answer, or {@link #NONE}. */
    static HeatShips get() {
        return Holder.ships;
    }

    /** Sets the answer (Meteodyne's setup, when Sable is loaded). */
    static void use(HeatShips ships) {
        Holder.ships = ships;
    }

    /** Adds the middle of every ship in the level (each ship is an anchor of its own). */
    default void addCentres(ServerLevel level, List<Vec3> out) {
    }

    /** Adds every loaded chunk of every ship in the level, as {@code ChunkPos} longs. */
    default void addLoadedChunks(ServerLevel level, LongSet out) {
    }

    /** The ship (not removed) whose plot holds chunk ({@code cx}, {@code cz}), or null for the world. */
    default Object containing(Level level, int cx, int cz) {
        return null;
    }

    /** The ship (not removed) whose plot holds {@code pos}, or null for the world or a plot with no ship. */
    default Object shipAt(Level level, BlockPos pos) {
        return null;
    }

    /**
     * {@code box} (a region's {@code minX, minY, minZ, maxX, maxY, maxZ}) cut to the ship's plot (its whole reserved
     * grid), or null if too little is left.
     */
    default int[] clipToPlot(int[] box, Object ship) {
        return box;
    }

    /** Where the ship is in the world: its bounds. */
    default AABB worldBounds(Object ship) {
        throw new IllegalArgumentException("not a ship: " + ship);
    }

    /**
     * What the ship's regions take from where it is now: the outside air at its world centre, the wind against it in
     * its own frame, whether a hangar roofs it, and whether one of {@code people} is within {@code near} blocks.
     */
    default HeatWorld.ShipAir shipAir(ServerLevel level, Object ship, List<Vec3> people, double near) {
        throw new IllegalArgumentException("not a ship: " + ship);
    }

    /** Where the registered answer is kept. */
    final class Holder {
        private static volatile HeatShips ships = NONE;

        private Holder() {
        }
    }
}
