// Written by Claude (GreedyFluids M7b); edited by Claude (b4: an engine override for game tests; M7e: readers; M7f f3:
// the local-air domains are gone; AIRFLOW is the default and game-test servers run LEGACY; M8a: the rule on its own,
// for the server-start game test).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Which simulation runs the air (GreedyFluids M7b; Bright, 2026-11-14): the legacy heat regions, or GreedyFluids air
 * regions around players. The two are exclusive: under {@link Engine#AIRFLOW} the legacy regions (ships' rooms
 * included) stand down ({@code HeatWorld.legacyEnabled}); the readers' room offset comes from
 * {@link AirflowTemperatures#offset} instead (M7 part e).
 *
 * <p>Taken from {@code [heat] engine} when the server starts (server configs load just before
 * {@link ServerAboutToStartEvent}) and kept for the session, so a config edit needs a restart. LEGACY with no server.
 * AIRFLOW is the config's default (M7 part f3). A game-test server runs LEGACY whatever the config says: the legacy
 * heat tests need its regions, and the airflow game tests make their own regions ({@link #useForTests} and
 * {@code AirflowRegions.useForTests}).
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
        boolean gameTests = event.getServer() instanceof GameTestServer;
        engine = choose(gameTests, WeatherConfig.heatEngine());
        // Logged either way, so the log always shows which engine a session ran.
        Meteodyne.LOGGER.info("[heat] engine is {}{}", engine, gameTests ? " (game-test server)"
                : engine == Engine.AIRFLOW ? ": the legacy heat regions are off" : "");
    }

    /** The engine a server runs: LEGACY on a game-test server, else the configured one. */
    static Engine choose(boolean gameTestServer, Engine configured) {
        return gameTestServer ? Engine.LEGACY : configured;
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        engine = Engine.LEGACY;
    }

    /**
     * Sets the engine mid-session, for game tests of the switch (their own batch, set back in a finally). Only the
     * legacy side follows: the airflow regions start at server start alone.
     */
    static void useForTests(Engine e) {
        engine = e;
    }

    private Airflow() {
    }
}
