package dev.brights0ng.enginesandempires.weather.climate;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.FieldEnv;
import dev.brights0ng.enginesandempires.weather.field.Moisture;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatRadiant;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatWorld;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import dev.brights0ng.enginesandempires.weather.wind.WindSources;
import dev.brights0ng.paradynelib.ships.ShipCover;
import dev.brights0ng.paradynelib.wind.Shelter;
import dev.brights0ng.paradynelib.wind.ShelterProbe;
import dev.brights0ng.paradynelib.wind.WindBlend;
import dev.brights0ng.paradynelib.wind.WindColumn;
import dev.brights0ng.paradynelib.wind.WindConfig;
import dev.brights0ng.paradynelib.wind.WindParams;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * What the air does to a person beyond its temperature (weather phase 8e-2; Bright, 2026-10-15): the wind they feel,
 * the humidity they breathe and the sun on them, worked out on the server for the Cold Sweat modifier and the client.
 *
 * <ul>
 *   <li><b>Wind:</b> the same wind a ship feels ({@link WindSources#column} blended by height with
 *       {@link WindBlend#aloftShare}: surface wind near the ground, aloft wind up high), cut by shelter
 *       ({@link ShelterProbe} on the person's own box, and the ships' blocks too, so a cabin shelters) and measured
 *       against the person's own motion, so walking into the wind or riding a fast ship feels windier.</li>
 *   <li><b>Humidity:</b> the atmosphere field's moisture against what the air could hold, so it follows fronts.</li>
 *   <li><b>Sun:</b> {@link SunWarmth}.</li>
 *   <li><b>Local heat</b> (8f-3): the heat simulation's room air around them ({@code HeatWorld.offsetAt}), which is
 *       the air the wind chill and heat index then work on, and the radiant warmth of sources in view
 *       ({@code HeatRadiant}). Aboard a ship (8f-5), the ship's rooms, and radiant warmth from the ship's sources
 *       and the world's (through its hull).</li>
 * </ul>
 * A sample is cached per person for half a second, so the modifier and the client sync share one computation.
 */
public final class PersonClimate {

    /**
     * What was worked out, at game tick {@code time}: {@code roomC} is how much warmer the air around them is than
     * outside (heat simulation), {@code radiantC} the warmth felt straight from sources in view.
     */
    public record Sample(double windMps, double humidity, double sunC, double roomC, double radiantC, long time) {
        /** Nothing known: still air, middling humidity, no sun. */
        public static final Sample NONE = new Sample(0, 0.5, 0, 0, 0, Long.MIN_VALUE);
    }

    /** Ticks a sample is kept. */
    static final int REFRESH = 10;
    /** A displacement faster than this between samples is a teleport, not motion, m/s. */
    static final double MAX_SPEED = 120;
    /** The person's box. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    /** Where the room's air and radiant heat are read: the middle of the body, above the feet. */
    static final double BODY_MIDDLE = 0.9;

    private static final class State {
        long time = Long.MIN_VALUE;
        double x, y, z;
        double vx, vz;
        Sample sample;
    }

    private static final Map<Player, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    /** The person's sample now (cached for {@link #REFRESH} ticks). */
    public static Sample sample(ServerLevel level, Player player) {
        if (!WeatherOwnership.owns(level)) {
            return Sample.NONE;
        }
        State st;
        synchronized (STATES) {
            st = STATES.computeIfAbsent(player, p -> new State());
        }
        long now = level.getGameTime();
        if (st.sample != null && now >= st.sample.time() && now - st.sample.time() < REFRESH) {
            return st.sample;
        }
        Vec3 at = worldPosition(level, player);

        // Their own motion in the world, from how far they moved since the last sample
        long dt = now - st.time;
        if (st.time != Long.MIN_VALUE && dt >= 1 && dt <= 100) {
            double seconds = dt / 20.0;
            double vx = (at.x - st.x) / seconds;
            double vz = (at.z - st.z) / seconds;
            if (Math.hypot(vx, vz) > MAX_SPEED) {
                vx = 0;
                vz = 0;
            }
            st.vx = vx;
            st.vz = vz;
        } else {
            st.vx = 0;
            st.vz = 0;
        }
        st.time = now;
        st.x = at.x;
        st.y = at.y;
        st.z = at.z;

        double wind = windOn(level, at, st.vx, st.vz);
        // Under cover the surface's humidity doesn't reach them: the flat underground value (or the cave biome's)
        double humidity = UndergroundProbe.humidity(level, BlockPos.containing(at), humidity(level, at.x, at.z));
        double sun = SunWarmth.on(level, player);
        double room = 0;
        double radiant = 0;
        HeatWorld heat = HeatWorld.of(level);
        if (heat != null && WeatherConfig.heat().enabled()) {
            Vec3 body = at.add(0, BODY_MIDDLE, 0);
            // Where the body is in a ship's own grid, if it is aboard one (or the player is kept in its plot)
            boolean keptInPlot = ShipCover.inPlot(level, player.blockPosition());
            Vec3 plotBody = keptInPlot
                    ? player.position().add(0, BODY_MIDDLE, 0)
                    : ShipCover.plotPoint(level, body.x, body.y, body.z);
            room = heat.roomAt(keptInPlot ? plotBody : body);
            radiant = HeatRadiant.at(level, body, plotBody);
        }
        st.sample = new Sample(wind, humidity, sun, room, radiant, now);
        return st.sample;
    }

    /**
     * The felt-temperature offset, C, for this sample with outside air of {@code airC}: the room's warmth, then heat
     * index and wind chill on that air, the sun, and radiant warmth.
     */
    public static double offsetC(Sample sample, double airC) {
        Apparent.Settings settings = WeatherConfig.apparent();
        double air = airC + sample.roomC();
        double chill = Apparent.windChillDrop(air, sample.windMps());
        double heat = Apparent.heatIndexRise(air, sample.humidity());
        return sample.roomC() - settings.apply(chill) + settings.apply(heat) + sample.sunC() + sample.radiantC();
    }

    /** Where the person is in the world (a person on a ship is looked up through the ship's pose). */
    static Vec3 worldPosition(ServerLevel level, Player player) {
        BlockPos feet = player.blockPosition();
        if (ShipCover.inPlot(level, feet)) {
            Vec3 w = ShipCover.worldCentre(level, feet);
            if (w != null) {
                return new Vec3(w.x, w.y + (player.getY() - (feet.getY() + 0.5)), w.z);
            }
        }
        return player.position();
    }

    /** The speed, m/s, of the wind against the person at {@code at} moving ({@code vx}, {@code vz}). */
    public static double windOn(ServerLevel level, Vec3 at, double vx, double vz) {
        WindColumn column = WindSources.column(level, at.x, at.z);
        double windX = 0;
        double windZ = 0;
        if (column != null) {
            WindParams params = WindConfig.params();
            BlockPos here = BlockPos.containing(at);
            double ground = level.hasChunkAt(here)
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, here.getX(), here.getZ())
                    : level.getSeaLevel();
            double[] w = column.at(WindBlend.aloftShare(at.y, ground, params.surfaceLayer(), params.aloftHeight()));
            double speed = Math.hypot(w[0], w[1]);
            if (speed > 1e-6) {
                double exposure = exposure(level, at, w[0] / speed, w[1] / speed, params);
                windX = w[0] * exposure;
                windZ = w[1] * exposure;
            }
        }
        return Apparent.relativeWind(windX, windZ, vx, vz);
    }

    /** How open to the wind the person is, 0 (sheltered) to 1 (open): the world's blocks and any ship's. */
    public static double exposure(ServerLevel level, Vec3 at, double dirX, double dirZ, WindParams params) {
        double[] coverage = new double[Shelter.FACES];
        ShelterProbe.measure(level, at.x - HALF_WIDTH, at.y, at.z - HALF_WIDTH, at.x + HALF_WIDTH, at.y + HEIGHT,
                at.z + HALF_WIDTH, params.sideReach(), params.roofReach(), coverage);
        int reach = Math.max(params.sideReach(), params.roofReach());
        if (ShipCover.near(level, at.x, at.y + HEIGHT / 2, at.z, reach)) {
            double[] ships = shipCoverage(level, at, params);
            for (int i = 0; i < Shelter.FACES; i++) {
                coverage[i] = 1 - (1 - coverage[i]) * (1 - ships[i]);
            }
        }
        return Shelter.exposure(coverage, dirX, dirZ, params.roofWeight());
    }

    /** Coverage by ships' blocks per face: a probe at knee and chest height to each side, one up from the head. */
    static double[] shipCoverage(ServerLevel level, Vec3 at, WindParams params) {
        double[] out = new double[Shelter.FACES];
        out[Shelter.TOP] = blocked(level, at.x, at.y + HEIGHT, at.z, 0, 1, 0, params.roofReach());
        double[] heights = {at.y + 0.4, at.y + 1.4};
        int[][] sides = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        int[] faces = {Shelter.NORTH, Shelter.SOUTH, Shelter.WEST, Shelter.EAST};
        for (int f = 0; f < sides.length; f++) {
            double sum = 0;
            for (double y : heights) {
                sum += blocked(level, at.x, y, at.z, sides[f][0], 0, sides[f][1], params.sideReach());
            }
            out[faces[f]] = sum / heights.length;
        }
        return out;
    }

    private static double blocked(ServerLevel level, double x, double y, double z, double dx, double dy, double dz,
                                  int reach) {
        return ShipCover.rayObstruction(level, x, y, z, dx, dy, dz, 0.3, reach, PersonClimate::windClass) >= 2 ? 1 : 0;
    }

    /** What a ship block does to wind: solid ones stop it (2), the rest let it by (0). */
    static int windClass(BlockState state) {
        return !state.isAir() && state.blocksMotion() ? 2 : 0;
    }

    /**
     * The <b>outside</b> air's relative humidity at (x, z), 0-1: the field's moisture against what its air could hold
     * (as the weather map shows it), or the region's own where the field doesn't reach. Under cover the person's own
     * humidity is {@link UndergroundProbe#humidity} of this.
     */
    public static double humidity(ServerLevel level, double x, double z) {
        WeatherSim sim = WeatherSim.of(level);
        if (sim != null && sim.field().covers(x, z)) {
            AtmosphereField field = sim.field();
            FieldEnv env = sim.readEnv();
            double q = field.sample(AtmosphereField.Var.Q, x, z, env);
            double t = field.sample(AtmosphereField.Var.T, x, z, env);
            double capacity = Moisture.capacity(t);
            if (capacity > 1e-6 && Double.isFinite(q)) {
                return Math.max(0, Math.min(1, q / capacity));
            }
        }
        return Math.max(0, Math.min(1, Climate.field(level).regional(x, z).humidity()));
    }

    private PersonClimate() {
    }
}
