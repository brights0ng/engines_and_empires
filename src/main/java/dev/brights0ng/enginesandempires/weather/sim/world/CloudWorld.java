package dev.brights0ng.enginesandempires.weather.sim.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudDiagnostics;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSim;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSyncClient;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSyncPayload;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.sim.PressureField;
import dev.brights0ng.enginesandempires.weather.sim.SimMath;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The clouds in the Overworld (phase 4a of {@code claude/weather-backbone-plan.md}): runs the spawner ({@link CloudSim})
 * every {@value #PASS} ticks against the atmosphere field and the weather systems, and keeps each player's client up to
 * date with the clouds near them ({@link CloudSyncPayload}: only new, changed and gone clouds). The clouds are saved
 * with the weather ({@code WeatherSimData}).
 *
 * <p>Clouds live on game time (not the simulation's time, which {@code /eae weather step} can move ahead), since
 * clients age and move them by their own game clock.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class CloudWorld {

    /** Ticks between spawner passes (offset from the systems step, which runs on multiples of 100). */
    public static final int PASS = 40;
    static final int PASS_OFFSET = 20;
    /** 2: clouds carry the velocity they are easing into (2026-10-07). */
    private static final String NET_VERSION = "2";

    /** Per player: the version of each cloud they have. */
    private static final Map<UUID, Map<UUID, Integer>> SENT = new HashMap<>();
    /** The server's cloud shapes, worked out once per tick. */
    private static List<CloudShape> serverShapes = List.of();
    private static long serverShapesTick = Long.MIN_VALUE;
    private static CloudSim.Report lastReport;
    /** The storms' outflow as of the last pass (read for every ship every tick, so replaced whole). */
    private static volatile dev.brights0ng.enginesandempires.weather.cloud.sim.GustFronts gusts =
            dev.brights0ng.enginesandempires.weather.cloud.sim.GustFronts.NONE;

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NET_VERSION).optional();
        registrar.playToClient(CloudSyncPayload.TYPE, CloudSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> CloudSyncClient.accept(payload.reset(),
                        payload.upserts(), payload.removed())));
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())
                || level.getGameTime() % PASS != PASS_OFFSET) {
            return;
        }
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            return;
        }
        pass(level, sim);
        sync(level, sim);
    }

    private static void pass(ServerLevel level, WeatherSim sim) {
        long now = level.getGameTime();
        List<CloudSim.Anchor> anchors = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            anchors.add(new CloudSim.Anchor(p.getX(), p.getZ()));
        }
        SplittableRandom rng = new SplittableRandom(SimMath.hash(level.getSeed(), 0xC10D5L, now));
        try {
            CloudSim.Env env = env(level, sim);
            lastReport = sim.cloudSim().pass(now, anchors, env, WeatherConfig.cloudSettings(), rng);
            gusts = dev.brights0ng.enginesandempires.weather.cloud.sim.GustFronts.of(sim.cloudSim().clouds(), now);
            sim.markDirty();
            CloudAudit.afterPass(level, sim, env, lastReport);
        } catch (RuntimeException ex) {
            EnginesAndEmpiresMod.LOGGER.warn("Weather: the cloud pass failed", ex);
        }
    }

    /** What the clouds need from the world, now. */
    public static CloudSim.Env env(ServerLevel level, WeatherSim sim) {
        AtmosphereField field = sim.field();
        PressureField.Snapshot snapshot = sim.snapshot();
        CloudDiagnostics.Systems systems = CloudDiagnostics.Systems.of(sim.systems());
        double seconds = sim.seconds();
        double season = sim.cachedSeason();
        long seed = sim.seed();
        int sea = level.getSeaLevel();
        long dayTime = level.getDayTime();
        return new CloudSim.Env() {
            @Override
            public CloudDiagnostics.Air air(double x, double z) {
                double[] c = field.cellAt(x, z);
                if (c == null) {
                    // Outside the field (shouldn't happen near players): dry, neutral air makes no cloud.
                    return new CloudDiagnostics.Air(10, 0.25, 0, 0, 10, 0.3, 0, 0, 0, 0, 0, 0, 0, 0, 0);
                }
                double cell = AtmosphereField.CELL;
                double[] e = field.cellAt(x + cell, z);
                double[] w = field.cellAt(x - cell, z);
                double[] s = field.cellAt(x, z + cell);
                double[] n = field.cellAt(x, z - cell);
                double slopeX = e != null && w != null ? (e[7] - w[7]) / (2 * cell) : 0;
                double slopeZ = s != null && n != null ? (s[7] - n[7]) / (2 * cell) : 0;
                double[] wind = wind(x, z);
                return new CloudDiagnostics.Air(c[0], c[1], c[2], c[3], c[4], c[5], (int) c[6], c[7], slopeX, slopeZ,
                        wind[0], wind[1], wind[2], wind[3], Temperature.heightCorrection(sea, sea + c[7]));
            }

            @Override
            public double[] wind(double x, double z) {
                return PressureField.wind(snapshot, sim.jet(), x, z, seconds, season, seed);
            }

            @Override
            public double[] steadyWind(double x, double z) {
                return PressureField.steadyWind(snapshot, sim.jet(), x, z, seconds, season, seed);
            }

            @Override
            public CloudDiagnostics.Systems systems() {
                return systems;
            }

            @Override
            public long dayTime() {
                return dayTime;
            }

            @Override
            public double season() {
                return season;
            }

            @Override
            public double drain(double x, double z, double mm) {
                return field.drain(x, z, mm);
            }
        };
    }

    // ---- sync -----------------------------------------------------------------------------------------------------

    private static void sync(ServerLevel level, WeatherSim sim) {
        long now = level.getGameTime();
        CloudSim.Settings settings = WeatherConfig.cloudSettings();
        double range = Math.max(settings.layerRadius(), settings.heapRadius()) + 2048;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            online.add(player.getUUID());
            Map<UUID, Integer> sent = SENT.get(player.getUUID());
            boolean reset = sent == null;
            if (sent == null) {
                sent = new HashMap<>();
                SENT.put(player.getUUID(), sent);
            }
            List<SimCloud> upserts = new ArrayList<>();
            Set<UUID> keep = new HashSet<>();
            for (SimCloud c : sim.cloudSim().clouds()) {
                if (Math.hypot(c.xAt(now) - player.getX(), c.zAt(now) - player.getZ()) > range) {
                    continue;
                }
                keep.add(c.id);
                Integer v = sent.get(c.id);
                if (v == null || v != c.version) {
                    upserts.add(c);
                    sent.put(c.id, c.version);
                }
            }
            List<UUID> removed = new ArrayList<>();
            Iterator<UUID> it = sent.keySet().iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                if (!keep.contains(id)) {
                    removed.add(id);
                    it.remove();
                }
            }
            send(player, reset, upserts, removed);
        }
        // Players who left the Overworld (or the game) forget its clouds.
        Iterator<Map.Entry<UUID, Map<UUID, Integer>>> it = SENT.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<UUID, Integer>> e = it.next();
            if (!online.contains(e.getKey())) {
                ServerPlayer gone = level.getServer().getPlayerList().getPlayer(e.getKey());
                if (gone != null) {
                    PacketDistributor.sendToPlayer(gone, new CloudSyncPayload(true, List.of(), List.of()));
                }
                it.remove();
            }
        }
    }

    private static void send(ServerPlayer player, boolean reset, List<SimCloud> upserts, List<UUID> removed) {
        if (!reset && upserts.isEmpty() && removed.isEmpty()) {
            return;
        }
        int from = 0;
        boolean first = true;
        do {
            int to = Math.min(upserts.size(), from + CloudSyncPayload.MAX_UPSERTS);
            List<SimCloud> part = new ArrayList<>(upserts.subList(from, to));
            PacketDistributor.sendToPlayer(player, new CloudSyncPayload(first && reset, part,
                    first ? removed : List.of()));
            first = false;
            from = to;
        } while (from < upserts.size());
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    /** The clouds over {@code level} as shapes, at its current tick (empty outside the Overworld). */
    public static List<CloudShape> shapes(ServerLevel level) {
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            return List.of();
        }
        long now = level.getGameTime();
        if (now != serverShapesTick) {
            String dimension = level.dimension().location().toString();
            List<CloudShape> out = new ArrayList<>();
            for (SimCloud c : sim.cloudSim().clouds()) {
                if (!c.over(now)) {
                    out.addAll(c.shapes(dimension, now));
                }
            }
            serverShapes = out;
            serverShapesTick = now;
        }
        return serverShapes;
    }

    /** The last pass's report, or null. */
    public static CloudSim.Report lastReport() {
        return lastReport;
    }

    /** The storms' outflow wind at the surface at (x, z) now, m/s {x, z} (Overworld only; zero elsewhere). */
    public static double[] gust(ServerLevel level, double x, double z) {
        return gusts.at(x, z, level.getGameTime());
    }

    /** How many storms are blowing outflow. */
    public static int gustCount() {
        return gusts.size();
    }

    /** The diagnosis at (x, z) now. */
    public static CloudDiagnostics.Need need(ServerLevel level, double x, double z) {
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            return null;
        }
        CloudSim.Env env = env(level, sim);
        return CloudDiagnostics.diagnose(x, z, env.air(x, z), env.systems(), env.dayTime(), env.season());
    }

    /** Spawns a mature cloud of {@code type} at (x, z) (debug). */
    public static SimCloud spawn(ServerLevel level, CloudType type, double x, double z) {
        return spawn(level, type, x, z, Double.NaN);
    }

    /** As {@link #spawn(ServerLevel, CloudType, double, double)}, a heap cloud of size {@code size} (NaN: random). */
    public static SimCloud spawn(ServerLevel level, CloudType type, double x, double z, double size) {
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            return null;
        }
        long now = level.getGameTime();
        SplittableRandom rng = new SplittableRandom(SimMath.hash(level.getSeed(), 0xDEB6L, now, (long) x, (long) z));
        SimCloud c = Double.isNaN(size) ? sim.cloudSim().spawnAt(type, x, z, now, env(level, sim), rng)
                : sim.cloudSim().spawnAt(type, x, z, now, env(level, sim), rng, size);
        sim.markDirty();
        serverShapesTick = Long.MIN_VALUE;
        return c;
    }

    /** Removes every cloud (debug, and after {@code /eae weather step}); clients forget theirs. */
    public static void clear(ServerLevel level) {
        WeatherSim sim0 = WeatherSim.of(level);
        if (sim0 != null) {
            sim0.cloudSim().clear();
        }
        resync(level);
    }

    /**
     * Sends every player the whole cloud list afresh (after clouds were removed outside a pass): clients drop what they
     * have and the next pass sends what's left.
     */
    public static void resync(ServerLevel level) {
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            return;
        }
        gusts = dev.brights0ng.enginesandempires.weather.cloud.sim.GustFronts.NONE;
        sim.markDirty();
        for (Map<UUID, Integer> sent : SENT.values()) {
            sent.clear();
        }
        for (ServerPlayer p : level.players()) {
            PacketDistributor.sendToPlayer(p, new CloudSyncPayload(true, List.of(), List.of()));
        }
        serverShapesTick = Long.MIN_VALUE;
    }

    private CloudWorld() {
    }
}
