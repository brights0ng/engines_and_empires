package dev.brights0ng.enginesandempires.weather.rain;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Localized weather settings. A SERVER config ({@code serverconfig/engines_and_empires-weather-server.toml} in the world
 * folder); the values clients need (the height cooling) are sent to them with the weather sync.
 */
public final class WeatherConfig {

    public static final String FILE_NAME = "engines_and_empires-weather-server.toml";

    /** Defaults, also used before the config loads (and in tests). */
    public static final double DEFAULT_HEIGHT_COOLING_SCALE = 5.0;
    public static final double DEFAULT_MAX_HEIGHT_COOLING = 30.0;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Air temperature.").push("temperature");
    }

    public static final ModConfigSpec.DoubleValue HEIGHT_COOLING_SCALE = BUILDER
            .comment("How much faster air cools with height than the standard atmosphere with a block taken as a",
                    "metre (0.65 C per 100 blocks). 5 matches the pack's x0.2 scale (a block is 5 m), so mountains",
                    "get snowcaps; 1 is a block as a metre; 0 turns height cooling off.",
                    "Rain or snow, snow piling up and ice all follow it.")
            .defineInRange("heightCoolingScale", DEFAULT_HEIGHT_COOLING_SCALE, 0.0, 20.0);

    public static final ModConfigSpec.DoubleValue MAX_HEIGHT_COOLING = BUILDER
            .comment("The most height can cool the air, in degrees C.")
            .defineInRange("maxHeightCooling", DEFAULT_MAX_HEIGHT_COOLING, 0.0, 100.0);

    static {
        BUILDER.pop().comment("Rain and snow.").push("precipitation");
    }

    public static final ModConfigSpec.BooleanValue LOCALIZED = BUILDER
            .comment("Whether the pack owns the Overworld's weather: vanilla's global rain cycle held clear, and",
                    "rain, snow and thunder coming only from the pack's clouds, by position. False leaves the",
                    "Overworld's weather to vanilla.")
            .define("localized", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double heightCoolingScale() {
        return SPEC.isLoaded() ? HEIGHT_COOLING_SCALE.get() : DEFAULT_HEIGHT_COOLING_SCALE;
    }

    public static double maxHeightCooling() {
        return SPEC.isLoaded() ? MAX_HEIGHT_COOLING.get() : DEFAULT_MAX_HEIGHT_COOLING;
    }

    public static boolean localized() {
        return !SPEC.isLoaded() || LOCALIZED.get();
    }

    private WeatherConfig() {
    }
}
