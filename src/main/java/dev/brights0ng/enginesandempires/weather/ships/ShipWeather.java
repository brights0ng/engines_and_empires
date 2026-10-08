package dev.brights0ng.enginesandempires.weather.ships;

import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.surface.SurfaceWeather;
import dev.brights0ng.enginesandempires.weather.wind.WindColumn;
import dev.brights0ng.enginesandempires.weather.wind.WindSources;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Weather on ships' decks (phase 5d of the weather backbone; Bright, 2026-10-08): snow, glaze and hail land on a ship's
 * exposed blocks as on the ground's ({@link SurfaceWeather}'s rules), with the weather and air where the ship really is.
 *
 * <ul>
 *   <li>Once a second per ship (staggered), at the ground's density: about one visit per deck column per in-game
 *       hour. A column's top is found in the ship's own frame (its plot's heightmap), then checked against the sky
 *       where it is in the world (terrain or another ship over it shelters it).</li>
 *   <li><b>Tilt:</b> a ship tilted more than {@link #MAX_TILT_DEGREES} collects nothing (what is there stays put, and
 *       still melts).</li>
 *   <li><b>Speed:</b> faster than {@link #STRIP_SPEED} m/s, snow is stripped off (all snow layers, not glaze, and not
 *       snow under glaze) and doesn't settle; glaze still builds (freezing rain ices ships in flight).</li>
 *   <li>Water doesn't freeze and ice doesn't thaw on ships (Bright: not needed).</li>
 *   <li>Snow and glaze weigh their ship down as Sable's "super light" blocks (0.25), by the {@code sable:super_light}
 *       tag (vanilla snow is in it already; glaze is added).</li>
 * </ul>
 * Only ships within {@link #PLAYER_RANGE} blocks of a player are visited.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class ShipWeather {

    /** Steeper than this (degrees from level) and a deck collects nothing (Bright, 2026-10-08: 45). */
    public static final double MAX_TILT_DEGREES = ShipRules.MAX_TILT_DEGREES;
    /** Faster than this (m/s) the airflow strips snow off and keeps it off (Bright, 2026-10-08: 20). */
    public static final double STRIP_SPEED = ShipRules.STRIP_SPEED;
    /** Ships further than this from every player aren't visited. */
    static final double PLAYER_RANGE = 256;
    private static final int PERIOD = SurfaceWeather.PERIOD;
    /** At most this many deck columns a ship per visit. */
    private static final int MAX_COLUMNS = 64;

    /** Per ship: whether it was going fast last time, and the visits owed below a whole column. */
    static final class State {
        boolean fast;
        double owed;
    }

    private static final Map<SubLevel, State> STATES = new IdentityHashMap<>();

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !WeatherOwnership.owns(level)) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        long tick = level.getGameTime();
        STATES.keySet().removeIf(SubLevel::isRemoved);
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved() || Math.floorMod(tick + ship.getUniqueId().hashCode(), PERIOD) != 0) {
                continue;
            }
            tickShip(level, container, ship, level.random);
        }
    }

    /** One visit to a ship, if a player is near it. */
    static void tickShip(ServerLevel level, SubLevelContainer container, ServerSubLevel ship, RandomSource random) {
        visitShip(level, container, ship, random, null, Double.NaN, -1);
    }

    /**
     * One visit to a ship. For the game tests: {@code here} (non-null) is what falls on it instead of the weather,
     * {@code t} (finite) the air temperature instead of the air's, and {@code columns} (0 or more) how many deck columns
     * to visit instead of the ground's density; with any of them set, no player needs to be near.
     */
    public static void visitShip(ServerLevel level, SubLevelContainer container, ServerSubLevel ship,
                                 RandomSource random, LocalWeather.Here here, double t, int columns) {
        boolean test = here != null || Double.isFinite(t) || columns >= 0;
        BoundingBox3ic box = ship.getPlot().getBoundingBox();
        MassData mass = ship.getMassTracker();
        Vector3dc com = mass == null ? null : mass.getCenterOfMass();
        if (box == null || box.volume() <= 0 || com == null) {
            return;
        }
        Pose3dc pose = ship.logicalPose();
        Vector3d centre = pose.transformPosition(com, new Vector3d());
        if (!test && !nearPlayer(level, centre)) {
            return;
        }
        State state = STATES.computeIfAbsent(ship, s -> new State());

        // Speed: strip the snow off as the ship gets going.
        Vector3d velocity = Sable.HELPER.getVelocity(level, com, new Vector3d());
        boolean fast = ShipRules.strips(velocity.length());
        if (fast && !state.fast) {
            strip(level, box);
        }
        state.fast = fast;

        // Tilt: how far its up is from the world's.
        Vector3d up = pose.transformNormal(new Vector3d(0, 1, 0), new Vector3d());
        boolean tilted = !ShipRules.collects(up.x, up.y, up.z);

        // The weather where it is: once per visit (a ship spans a few chunks at most).
        if (here == null) {
            here = LocalWeather.at(level, centre.x, centre.y, centre.z);
        }
        WindColumn wind = WindSources.column(level, centre.x, centre.z);
        double airX = wind == null ? 0 : (wind.surfaceX() + wind.aloftX()) / 2;
        double airZ = wind == null ? 0 : (wind.surfaceZ() + wind.aloftZ()) / 2;
        // The airflow over the deck, in the ship's frame (snow drifts against its walls downwind of that).
        Vector3d flow = pose.transformNormalInverse(new Vector3d(airX - velocity.x, 0, airZ - velocity.z),
                new Vector3d());
        boolean sunny = level.isDay() && here.cover() < 0.3;

        int width = box.maxX() - box.minX() + 1;
        int depth = box.maxZ() - box.minZ() + 1;
        int n;
        if (columns >= 0) {
            n = columns;
        } else {
            state.owed += width * (double) depth * SurfaceWeather.COLUMNS / 256.0;
            n = Math.min(MAX_COLUMNS, (int) Math.floor(state.owed));
            state.owed -= n;
        }
        for (int i = 0; i < n; i++) {
            int x = box.minX() + random.nextInt(width);
            int z = box.minZ() + random.nextInt(depth);
            visitColumn(level, container, ship, pose, x, z, box.minY(), here, t, tilted, fast, sunny, flow, random);
        }
    }

    /** One deck column (plot x, z): its top in the ship's frame, tested against the sky where it really is. */
    private static void visitColumn(ServerLevel level, SubLevelContainer container, SubLevel ship, Pose3dc pose, int x,
                                    int z, int minY, LocalWeather.Here here, double forcedT, boolean tilted,
                                    boolean fast, boolean sunny, Vector3d flow, RandomSource random) {
        LevelChunk chunk = container.getChunk(new ChunkPos(x >> 4, z >> 4));
        if (chunk == null) {
            return;
        }
        int topY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
        if (topY <= minY) {
            return;
        }
        int canopyY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        BlockPos top = new BlockPos(x, topY, z);
        BlockPos underCanopy = new BlockPos(x, Math.min(topY, canopyY), z);

        Vec3 world = pose.transformPosition(new Vec3(x + 0.5, topY, z + 0.5));
        boolean exposed = open(level, world);
        double t = Double.isFinite(forcedT) ? forcedT : Temperature.at(level, BlockPos.containing(world));
        if (!Double.isFinite(t)) {
            return;
        }
        Precip falling = null;
        if (exposed && !tilted && here.falling()) {
            falling = here.precip();
            if (LocalWeather.forced == null
                    && (falling == Precip.RAIN || falling == Precip.MIXED || falling == Precip.SNOW)) {
                falling = Precip.decide(t, 0, 0);
            }
            if (fast && falling == Precip.SNOW) {
                // The airflow keeps snow off (but not glaze).
                falling = null;
            }
        }
        SurfaceWeather.visit(level, top, underCanopy,
                new SurfaceWeather.Column(t, falling, here.strength(), sunny && exposed, flow.x, flow.z), random,
                false);
    }

    /** Whether a deck spot (world position) sees the sky: no terrain over it, and no ship (its own included). */
    static boolean open(ServerLevel level, Vec3 world) {
        BlockPos p = BlockPos.containing(world);
        if (level.isLoaded(p) && level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) > world.y + 0.5) {
            return false;
        }
        return !ShipCover.covered(level, world.x, world.y + 0.25, world.z);
    }

    /** Takes every snow layer off a ship (snow under glaze stays: the glaze holds it). */
    public static void strip(ServerLevel level, BoundingBox3ic box) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY() + 1; y++) {
                    if (level.getBlockState(p.set(x, y, z)).is(Blocks.SNOW)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static boolean nearPlayer(ServerLevel level, Vector3d at) {
        double r2 = PLAYER_RANGE * PLAYER_RANGE;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - at.x;
            double dz = p.getZ() - at.z;
            if (dx * dx + dz * dz < r2) {
                return true;
            }
        }
        return false;
    }

    private ShipWeather() {
    }
}
