// Written by Claude (GreedyFluids M7b).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Which simulation runs the air (GreedyFluids M7b; Bright, 2026-11-14): the legacy heat regions, or GreedyFluids air
 * regions around players. The two are exclusive: under {@link Engine#AIRFLOW} the legacy regions (ships' rooms
 * included) and the local-air domains stand down ({@code HeatWorld.legacyEnabled}), so nothing feels room heat until
 * the airflow regions are read (M7 part e).
 *
 * <p>Taken from {@code [heat] engine} when the server starts (server configs load just before
 * {@link ServerAboutToStartEvent}) and kept for the session, so a config edit needs a restart. LEGACY with no server.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class Airflow {

    /** The simulations {@code [heat] engine} chooses between. */
    public enum Engine {
        /** The heat regions around placed sources (weather phase 8f), ships' rooms included. */
        LEGACY,
        /** GreedyFluids air regions around players (GreedyFluids M7). */
        AIRFLOW
    }

    private static volatile Engine engine = Engine.LEGACY;

    /** The running server's engine; any thread. */
    public static Engine engine() {
        return engine;
    }

    /** Whether GreedyFluids runs the air on the running server; any thread. */
    public static boolean active() {
        return engine == Engine.AIRFLOW;
    }

    @SubscribeEvent
    static void onServerAboutToStart(ServerAboutToStartEvent event) {
        engine = WeatherConfig.heatEngine();
        // Logged either way, so the log always shows which engine a session ran.
        Meteodyne.LOGGER.info("[heat] engine is {}{}", engine,
                engine == Engine.AIRFLOW ? ": the legacy heat regions and local-air domains are off" : "");
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        engine = Engine.LEGACY;
    }

    private Airflow() {
    }
}
