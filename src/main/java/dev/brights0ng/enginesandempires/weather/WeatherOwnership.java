package dev.brights0ng.enginesandempires.weather;

import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import net.minecraft.world.level.Level;

/**
 * Whether the pack, rather than vanilla, owns the weather in a level: the Overworld only (Bright, 2026-10-05), and only
 * while {@code precipitation.localized} is on in the server config (synced to clients). Where it does, vanilla's global
 * rain cycle is held clear and every weather question is answered by position from the pack's clouds.
 */
public final class WeatherOwnership {

    public static boolean owns(Level level) {
        return level != null && Level.OVERWORLD.equals(level.dimension()) && LocalWeather.enabled(level);
    }

    private WeatherOwnership() {
    }
}
