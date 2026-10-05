package dev.brights0ng.enginesandempires.weather.wind;

import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The wind state of every physics object being pushed, and bringing it up to date once per game tick: its outline, its
 * shelter (every {@link WindParams#shelterInterval()} ticks), and the wind it feels. Server thread only.
 */
public final class WindShips {

    private static final Map<ServerSubLevel, ShipWind> SHIPS = new IdentityHashMap<>();

    /** The state of an object, made on first use. */
    static ShipWind of(ServerSubLevel subLevel) {
        return SHIPS.computeIfAbsent(subLevel, s -> new ShipWind());
    }

    /** The state of an object if it has one (for the debug command and tests). */
    public static ShipWind peek(ServerSubLevel subLevel) {
        return SHIPS.get(subLevel);
    }

    /** Forgets objects that are gone. */
    static void purge() {
        SHIPS.keySet().removeIf(SubLevel::isRemoved);
    }

    /**
     * Brings an object's state up to date for this game tick.
     *
     * @return the object's centre of mass in the world, or null if it has none (no blocks yet)
     */
    static Vector3d refresh(ServerLevel level, ServerSubLevel subLevel, ShipWind ship, WindParams params, long tick) {
        ship.lastTick = tick;
        MassData mass = subLevel.getMassTracker();
        Vector3dc com = mass == null ? null : mass.getCenterOfMass();
        if (com == null) {
            ship.speed = 0;
            return null;
        }
        if (!ship.built) {
            build(level, subLevel, ship);
        }
        Vector3d worldCom = subLevel.logicalPose().transformPosition(com, new Vector3d());

        if (tick >= ship.nextShelterTick) {
            ship.nextShelterTick = tick + params.shelterInterval();
            checkShelter(level, subLevel, ship, worldCom, params);
        }

        WindColumn column = WindSources.column(level, worldCom.x, worldCom.z);
        if (column == null) {
            ship.speed = 0;
            return worldCom;
        }
        double ground = Double.isNaN(ship.groundY) ? level.getSeaLevel() : ship.groundY;
        ship.aloftShare = WindBlend.aloftShare(worldCom.y, ground, params.surfaceLayer(), params.aloftHeight());
        double[] wind = column.at(ship.aloftShare);
        double target = Math.hypot(wind[0], wind[1]);
        if (target > 1e-6) {
            ship.dirX = wind[0] / target;
            ship.dirZ = wind[1] / target;
        }
        ship.speed = WindBlend.ramp(ship.speed, target, params.gustRampTicks());
        ship.pressure = DimensionPhysicsData.getAirPressure(level, worldCom);
        ship.exposure = Shelter.exposure(ship.coverage, ship.dirX, ship.dirZ, params.roofWeight());
        return worldCom;
    }

    /** Builds the outline from every solid block in the object's plot. */
    private static void build(ServerLevel level, ServerSubLevel subLevel, ShipWind ship) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null || bounds.volume() <= 0) {
            return;
        }
        ship.silhouette.clear();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    pos.set(x, y, z);
                    if (isSolid(level, pos, level.getBlockState(pos))) {
                        ship.silhouette.add(x, y, z);
                    }
                }
            }
        }
        ship.built = true;
    }

    private static void checkShelter(ServerLevel level, ServerSubLevel subLevel, ShipWind ship, Vector3d worldCom,
                                     WindParams params) {
        BoundingBox3dc box = subLevel.boundingBox();
        ShelterProbe.measure(level, box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                params.sideReach(), params.roofReach(), ship.coverage);
        BlockPos column = BlockPos.containing(worldCom.x, worldCom.y, worldCom.z);
        if (level.isLoaded(column)) {
            ship.groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        }
    }

    /**
     * Keeps a built outline current as a block in an object's plot changes. Called from Sable's own block-change hook
     * (see {@code SubLevelSilhouetteMixin}).
     */
    public static void onBlockChange(SubLevel subLevel, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(subLevel instanceof ServerSubLevel server)) {
            return;
        }
        ShipWind ship = SHIPS.get(server);
        if (ship == null || !ship.built) {
            return;
        }
        Level level = server.getLevel();
        boolean was = isSolid(level, pos, oldState);
        boolean now = isSolid(level, pos, newState);
        if (was != now) {
            if (now) {
                ship.silhouette.add(pos.getX(), pos.getY(), pos.getZ());
            } else {
                ship.silhouette.remove(pos.getX(), pos.getY(), pos.getZ());
            }
        }
    }

    /** The same test Sable's mass tracker uses, so the outline holds exactly the blocks that have mass. */
    private static boolean isSolid(Level level, BlockPos pos, BlockState state) {
        return !state.isAir() && VoxelNeighborhoodState.isSolid(level, pos, state);
    }

    private WindShips() {
    }
}
