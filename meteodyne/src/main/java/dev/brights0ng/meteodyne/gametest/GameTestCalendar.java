package dev.brights0ng.meteodyne.gametest;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * The game test server runs without the built-in calendar ({@code seasons.useBuiltinCalendar = false}; Bright, step 1c-B),
 * so climate tests never move with the time of day: no seasons, every day is the year's mean, as in the game tests before
 * the built-in calendar (split seam S11). Set once the server's configs are loaded, before the world is; only on the game
 * test server, never in a played world. It applies to every game test server Meteodyne runs in: the aggregate run and
 * Meteodyne's own bare run.
 *
 * <p>Set again whenever the weather config is reloaded from its file while the server runs (split step 2c): a run whose
 * game folder has no weather config yet writes the defaults on load, and NeoForge's file watcher then reloads that new
 * file, which put the calendar back on in the middle of the tests. The setting is never saved to the file.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class GameTestCalendar {

    /** Whether the calendar is pinned off (a game test server is running). */
    private static volatile boolean pinned;

    @SubscribeEvent
    static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) {
            return;
        }
        if (WeatherConfig.SPEC.isLoaded()) {
            pinned = true;
            WeatherConfig.USE_BUILTIN_CALENDAR.set(false);
            Meteodyne.LOGGER.info("Game test server: the built-in calendar is off (no seasons)");
        } else {
            Meteodyne.LOGGER.warn("Game test server: the weather config is not loaded yet; the built-in calendar stays as configured");
        }
    }

    /** Added to Meteodyne's mod bus by {@code Meteodyne}: pins the calendar off again after a reload from the file. */
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (pinned && event.getConfig().getSpec() == WeatherConfig.SPEC) {
            WeatherConfig.USE_BUILTIN_CALENDAR.set(false);
            Meteodyne.LOGGER.info("Game test server: the weather config was reloaded; the built-in calendar is off again");
        }
    }

    private GameTestCalendar() {
    }
}
