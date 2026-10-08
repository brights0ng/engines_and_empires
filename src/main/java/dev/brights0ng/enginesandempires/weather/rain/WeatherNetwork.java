package dev.brights0ng.enginesandempires.weather.rain;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.wind.WindColumn;
import dev.brights0ng.enginesandempires.weather.wind.WindSources;
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
 * Sends each player the wind and temperature around them once a second ({@link WeatherSyncPayload}), so their client
 * works out rain and snow the way the server does. Overworld only (where the pack's weather lives; sent even with the
 * pack's weather turned off, so clients learn that); it costs 25 temperature lookups per player per second.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class WeatherNetwork {

    private static final String VERSION = "5";
    private static final int INTERVAL = 20;
    /** The temperature grid: points per side and blocks between them (about 200 blocks across). */
    static final int GRID = 5;
    static final int SPACING = 48;

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION).optional();
        registrar.playToClient(WeatherSyncPayload.TYPE, WeatherSyncPayload.STREAM_CODEC,
                (payload, context) -> ClientWeather.accept(payload.toState()));
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % INTERVAL != 0
                || level.players().isEmpty() || !Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        LocalWeather.tickWind(level);
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, build(level, player.getX(), player.getZ()));
        }
    }

    /** The sync for a player at (x, z). */
    static WeatherSyncPayload build(ServerLevel level, double x, double z) {
        WindColumn w = WindSources.column(level, x, z);
        if (w == null) {
            w = WindColumn.CALM;
        }
        double[] drift = LocalWeather.driftWind(level, x, z);
        int sea = level.getSeaLevel();
        int ox = Math.floorDiv((int) Math.floor(x), SPACING) * SPACING - (GRID / 2) * SPACING;
        int oz = Math.floorDiv((int) Math.floor(z), SPACING) * SPACING - (GRID / 2) * SPACING;
        float[] t = new float[GRID * GRID];
        float[] h = new float[GRID * GRID];
        var field = dev.brights0ng.enginesandempires.weather.climate.Climate.field(level);
        for (int k = 0; k < GRID; k++) {
            for (int i = 0; i < GRID; i++) {
                t[k * GRID + i] = (float) Temperature.atSeaLevel(level, ox + i * SPACING, oz + k * SPACING);
                h[k * GRID + i] = (float) field.regional(ox + i * SPACING, oz + k * SPACING).humidity();
            }
        }
        var sim = dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim.of(level);
        double[] nose = sim == null ? new double[]{0, 0} : sim.warmNose(x, z);
        return new WeatherSyncPayload(WeatherConfig.localized(), (float) w.surfaceX(), (float) w.surfaceZ(),
                (float) w.aloftX(), (float) w.aloftZ(), (float) drift[0], (float) drift[1], sea,
                (float) WeatherConfig.heightCoolingScale(),
                (float) WeatherConfig.maxHeightCooling(), ox, oz, SPACING, GRID, t, h, (float) nose[0], (float) nose[1],
                LocalWeather.forced == null ? -1 : LocalWeather.forced.ordinal());
    }

    private WeatherNetwork() {
    }
}
