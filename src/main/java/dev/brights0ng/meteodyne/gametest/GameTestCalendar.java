package dev.brights0ng.meteodyne.gametest;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * The game test server runs without the built-in calendar ({@code seasons.useBuiltinCalendar = false}; Bright, step 1c-B),
 * so climate tests never move with the time of day: no seasons, every day is the year's mean, as in the game tests before
 * the built-in calendar (split seam S11). Set once the server's configs are loaded, before the world is; only on the game
 * test server, never in a played world.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class GameTestCalendar {

    @SubscribeEvent
    static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) {
            return;
        }
        if (WeatherConfig.SPEC.isLoaded()) {
            WeatherConfig.USE_BUILTIN_CALENDAR.set(false);
            Meteodyne.LOGGER.info("Game test server: the built-in calendar is off (no seasons)");
        } else {
            Meteodyne.LOGGER.warn("Game test server: the weather config is not loaded yet; the built-in calendar stays as configured");
        }
    }

    private GameTestCalendar() {
    }
}
