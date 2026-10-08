package dev.brights0ng.enginesandempires.weather.rain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.cloud.CloudSources;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;
import dev.brights0ng.enginesandempires.weather.wind.WindColumn;
import dev.brights0ng.enginesandempires.weather.wind.WindSources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * "What is falling here?", for any level, on either side: the clouds, the wind and the temperature for the side it runs
 * on, put through {@link RainModel}. The rule (Bright, 2026-10-02): gameplay goes by the precipitation at its own
 * position, and rain drawn at a spot is rain reported there, so the renderer and the gameplay hooks both ask this.
 *
 * <ul>
 *   <li><b>Clouds:</b> on the server, the simulation's own ({@link CloudSources}), read once per tick per level; on the
 *       client, the ones the renderer tracks, set every client tick ({@link #setClient}) at its smoothed positions.</li>
 *   <li><b>Wind:</b> the average of surface and aloft ({@link WindSources}), averaged over about the time rain takes to
 *       fall ({@link WindAverage}, 20 s), so the wet patch doesn't chase gusts; the client gets the server's average at
 *       its player ({@link ClientWeather}).</li>
 *   <li><b>What falls</b> (phase 5a, 2026-10-07): {@link Precip#decide} from the pack's air temperature at the spot
 *       ({@link Temperature}, height cooling included) and the warm layer aloft ahead of warm fronts
 *       ({@code WarmNose}): rain, mixed, snow, sleet or freezing rain; hail in bursts from strong thunderstorm cores
 *       ({@link RainModel#hailAt}). Synced on the client. Where the client can't say yet, the biome's own rain or
 *       snow.</li>
 *   <li><b>Dry country</b> (2026-10-08): rain thins with the region's air humidity ({@link RainModel#wetness}, the
 *       climate's regional field, smooth over hundreds of blocks; synced to clients), so deserts get only light rain
 *       and no biome border cuts it off.</li>
 * </ul>
 *
 * <p>Phase 0 of the weather backbone (2026-10-05): there are no clouds yet, so nothing falls anywhere.
 */
public final class LocalWeather {

    /**
     * What is falling at a spot.
     *
     * @param strength how hard, 0-1
     * @param thunder  whether from a thunder cloud
     * @param precip   what falls (null when nothing does)
     * @param cover    how covered by cloud the spot is, 0-1
     */
    public record Here(double strength, boolean thunder, Precip precip, double cover, UUID region, String type) {

        public static final Here NONE = new Here(0, false, null, 0, null, null);

        public boolean falling() {
            return strength > RainModel.MIN;
        }

        /** Whether what falls (or would fall here) is snow. */
        public boolean snow() {
            return precip == Precip.SNOW;
        }

        /** Whether something wet falls: anything but snow (sleet and hail come with rain). */
        public boolean raining() {
            return falling() && !snow();
        }

        public boolean thundering() {
            return falling() && thunder;
        }
    }

    private record Snapshot(long tick, List<RainModel.Cloud> clouds) {
    }

    private static final Map<ServerLevel, Snapshot> SERVER = new WeakHashMap<>();
    private static final Map<ServerLevel, VelocitySmoother> SERVER_DRIFT = new WeakHashMap<>();
    /** Per level: the wind rain drifts in, averaged over its fall. */
    private static final Map<ServerLevel, WindAverage> SERVER_WIND = new WeakHashMap<>();
    private static final VelocitySmoother CLIENT_DRIFT = new VelocitySmoother();
    private static volatile List<RainModel.Cloud> clientClouds = List.of();
    private static volatile String clientDimension;

    /** The client's clouds (the renderer's, at its smoothed positions), set every client tick. */
    public static void setClient(List<CloudShape> shapes, double time, RainModel.Positions positions, String dimension) {
        clientClouds = RainModel.build(shapes, time, positions, CLIENT_DRIFT);
        clientDimension = dimension;
    }

    public static void clearClient() {
        clientClouds = List.of();
        clientDimension = null;
    }

    /** The clouds over {@code level}, ready to sample. */
    public static List<RainModel.Cloud> clouds(Level level) {
        String dimension = level.dimension().location().toString();
        if (level instanceof ServerLevel server) {
            long tick = server.getGameTime();
            synchronized (SERVER) {
                Snapshot s = SERVER.get(server);
                if (s == null || s.tick() != tick) {
                    List<CloudShape> here = new ArrayList<>();
                    for (CloudShape c : CloudSources.server(server)) {
                        if (dimension.equals(c.dimension())) {
                            here.add(c);
                        }
                    }
                    VelocitySmoother drift = SERVER_DRIFT.computeIfAbsent(server, k -> new VelocitySmoother());
                    s = new Snapshot(tick, RainModel.build(here, tick, RainModel.RAW, drift));
                    SERVER.put(server, s);
                }
                return s.clouds();
            }
        }
        return dimension.equals(clientDimension) ? clientClouds : List.of();
    }

    /** Whether the pack's model is in charge of rain on {@code level}'s side (the server config, synced to clients). */
    public static boolean enabled(Level level) {
        return level instanceof ServerLevel ? WeatherConfig.localized() : ClientWeather.localized();
    }

    /** The instant wind rain falls through at (x, z) (the average of surface and aloft), m/s, or null. */
    private static double[] rawWind(ServerLevel level, double x, double z) {
        WindColumn w = WindSources.column(level, x, z);
        return w == null ? null : new double[]{(w.surfaceX() + w.aloftX()) / 2, (w.surfaceZ() + w.aloftZ()) / 2};
    }

    private static WindAverage windAverage(ServerLevel level) {
        synchronized (SERVER_WIND) {
            return SERVER_WIND.computeIfAbsent(level, k -> new WindAverage());
        }
    }

    /** Keeps the drift wind averaged around {@code level}'s players. Call once a second. */
    public static void tickWind(ServerLevel level) {
        WindAverage avg = windAverage(level);
        long tick = level.getGameTime();
        WindAverage.Raw raw = (x, z) -> rawWind(level, x, z);
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            avg.observe(p.getX(), p.getZ(), tick, raw);
        }
        avg.forget(tick);
    }

    /** The wind rain drifts in at (x, z) on the server, averaged over its fall, m/s ({0, 0} without wind). */
    public static double[] driftWind(ServerLevel level, double x, double z) {
        double[] w = windAverage(level).at(x, z, (px, pz) -> rawWind(level, px, pz));
        return w == null ? new double[2] : w;
    }

    /** What is falling at (x, y, z) in {@code level}. */
    public static Here at(Level level, double x, double y, double z) {
        List<RainModel.Cloud> clouds = clouds(level);
        if (clouds.isEmpty()) {
            return Here.NONE;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        Biome biome = level.getBiome(pos).value();
        double windX;
        double windZ;
        double temperature;
        double[] nose;
        double humidity;
        double time = level.getGameTime();
        if (level instanceof ServerLevel server) {
            double[] w = driftWind(server, x, z);
            windX = w[0];
            windZ = w[1];
            temperature = Temperature.at(server, pos);
            var sim = dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim.of(server);
            // (Only cold enough ground can turn a warm layer aloft into sleet or freezing rain: skip it otherwise.)
            nose = sim == null || !(temperature < Precip.MIXED_MAX) ? NO_NOSE : sim.warmNose(x, z);
            humidity = dev.brights0ng.enginesandempires.weather.climate.Climate.field(server).regional(x, z).humidity();
        } else {
            windX = ClientWeather.windX();
            windZ = ClientWeather.windZ();
            temperature = ClientWeather.temperature(x, y, z);
            nose = ClientWeather.warmNose();
            humidity = ClientWeather.humidity(x, z);
        }
        Precip kind = Double.isFinite(temperature) ? Precip.decide(temperature, nose[0], nose[1])
                : biome.coldEnoughToSnow(pos) ? Precip.SNOW : Precip.RAIN;
        Precip override = level instanceof ServerLevel ? forced : ClientWeather.forced();
        if (override != null) {
            kind = override;
        }
        RainModel.Sample s = RainModel.sample(clouds, x, y, z, windX, windZ, kind, time);
        if (s.hail() && override == null) {
            kind = Precip.HAIL;
        }
        // Dry country (2026-10-08, Bright: "dry by air humidity"): rain thins with the region's air humidity, smooth
        // over hundreds of blocks, rather than stopping at dry biomes' borders (which left rain in rivers through
        // deserts). Before the client's first sync, the biome's own say.
        double wet = Double.isFinite(humidity) ? RainModel.wetness(humidity) : biome.hasPrecipitation() ? 1 : 0;
        double strength = s.strength() * wet;
        if (strength <= RainModel.MIN) {
            return s.cover() > 0 ? new Here(0, false, kind, s.cover(), null, null) : Here.NONE;
        }
        return new Here(strength, s.thunder(), kind, s.cover(), s.region(), s.type());
    }

    private static final double[] NO_NOSE = {0, 0};

    /**
     * Debug ({@code /eae weather precip}): on the server, the kind everything falls as (null: the weather's own). Sent
     * to clients with the weather sync ({@link ClientWeather#forced}). Not saved.
     */
    public static volatile Precip forced;

    private LocalWeather() {
    }
}
