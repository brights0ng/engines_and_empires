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
        BUILDER.pop().comment("The climate baseline: what is normal anywhere before weather (see",
                "claude/weather-backbone-plan.md). Biome values are in the biome_climate data map.").push("climate");
    }

    /** Where cold and warm air masses come from. */
    public enum AirMassSource {
        /** From the biomes alone. */
        BIOME,
        /** From repeating climate bands along Z blended with the biomes. */
        BANDS
    }

    public static final ModConfigSpec.EnumValue<AirMassSource> AIR_MASS_SOURCE = BUILDER
            .comment("Where cold and warm air come from. BIOME: the biomes alone. BANDS: repeating climate bands",
                    "along Z (colder to the north, warmer to the south, spawn in the temperate middle) on top of",
                    "the biomes, so fronts have a consistent orientation.")
            .defineEnum("airMassSource", AirMassSource.BANDS);

    public static final ModConfigSpec.IntValue BAND_PERIOD = BUILDER
            .comment("BANDS: blocks along Z from one coldest band to the next (the warmest is half way).")
            .defineInRange("bandPeriod", 64000, 4000, 1_000_000);

    public static final ModConfigSpec.DoubleValue BAND_AMPLITUDE = BUILDER
            .comment("BANDS: how much colder or warmer the band centres are than the temperate middle, C.")
            .defineInRange("bandAmplitude", 20.0, 0.0, 40.0);

    public static final ModConfigSpec.DoubleValue BAND_WEIGHT = BUILDER
            .comment("BANDS: how much of the band offset reaches the temperature players feel (0 = air masses only,",
                    "1 = all of it).")
            .defineInRange("bandWeight", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue LOCAL_BIOME_WEIGHT = BUILDER
            .comment("How much the biome underfoot counts against the smoothed regional climate (0 = regional only,",
                    "1 = the biome alone). A small snowy patch in a warm region is colder the higher this is.")
            .defineInRange("localBiomeWeight", 0.5, 0.0, 1.0);

    static {
        BUILDER.pop().comment("Weather systems: the jet, lows (storms), highs and their fronts.").push("systems");
    }

    public static final ModConfigSpec.DoubleValue SYSTEM_SPEED = BUILDER
            .comment("How fast the jet steers weather systems at its core, blocks per second (winter about 1.2x,",
                    "summer about 0.6x). Together with the spacing it sets how often a front passes a spot.")
            .defineInRange("speed", 2.5, 0.1, 20.0);

    public static final ModConfigSpec.DoubleValue SYSTEM_SPACING = BUILDER
            .comment("Blocks between successive lows along a storm track (winter about 0.8x, summer about 1.4x).")
            .defineInRange("spacing", 12000.0, 2000.0, 100000.0);

    public static final ModConfigSpec.DoubleValue ZONE_RADIUS = BUILDER
            .comment("Blocks around each player kept supplied with weather systems.")
            .defineInRange("zoneRadius", 16000.0, 4000.0, 100000.0);

    public static final ModConfigSpec.DoubleValue JET_CORE = BUILDER
            .comment("The aloft wind at the jet's core, m/s (winter about 1.3x, summer about 0.7x). Clouds drift with",
                    "the wind aloft at 0.4 blocks a second per m/s, so this sets how fast the sky moves on calm days.",
                    "It doesn't change how fast weather systems travel (that is 'speed').")
            .defineInRange("jetCore", 8.0, 0.0, 60.0);

    public static final ModConfigSpec.DoubleValue BLOCK_CHANCE = BUILDER
            .comment("The chance a new high stalls and blocks (heat waves, cold snaps), before seasons.")
            .defineInRange("blockChance", 0.06, 0.0, 1.0);

    static {
        BUILDER.pop().comment("Clouds: where the spawner keeps them, and how many. Changes apply at the next pass.")
                .push("clouds");
    }

    public static final ModConfigSpec.DoubleValue CLOUD_LAYER_RADIUS = BUILDER
            .comment("How far from each player layer clouds (stratus, nimbostratus, alto-, cirro-) are kept, blocks.",
                    "A little past the clients' cloud draw distance, so distant decks show on the horizon.")
            .defineInRange("layerRadius", 4608.0, 1024.0, 16384.0);

    public static final ModConfigSpec.DoubleValue CLOUD_HEAP_RADIUS = BUILDER
            .comment("How far from each player heap clouds (cumulus up to cumulonimbus) are kept, blocks.")
            .defineInRange("heapRadius", 3072.0, 512.0, 16384.0);

    public static final ModConfigSpec.DoubleValue CLOUD_LAYER_SPACING = BUILDER
            .comment("Blocks between the slots layer clouds are kept on (each cloud fills about this much sky).")
            .defineInRange("layerSpacing", 1536.0, 512.0, 8192.0);

    public static final ModConfigSpec.IntValue CLOUD_MAX_PER_PLAYER = BUILDER
            .comment("The most clouds the world keeps per player online; new ones stop forming past it.")
            .defineInRange("maxPerPlayer", 320, 16, 4096);

    public static final ModConfigSpec.DoubleValue CLOUD_HEAP_DENSITY = BUILDER
            .comment("How many heap clouds (cumulus up to cumulonimbus) the sky shows, as a share of what the air would",
                    "build: 1 = the full count, 0.5 = half. Doesn't change where or when they form.")
            .defineInRange("heapDensity", 0.5, 0.0, 2.0);

    public static final ModConfigSpec.BooleanValue CLOUD_AUDIT = BUILDER
            .comment("Debug: write every new cloud and why, and a check of the sky around each player every 30 s, to",
                    "logs/weather-audit.log from the moment the world loads (also /eae weather clouds audit on|off).")
            .define("audit", false);

    static {
        BUILDER.pop().comment("Rain and snow.").push("precipitation");
    }

    public static final ModConfigSpec.BooleanValue LOCALIZED = BUILDER
            .comment("Whether the pack owns the Overworld's weather: vanilla's global rain cycle held clear, and",
                    "rain, snow and thunder coming only from the pack's clouds, by position. False leaves the",
                    "Overworld's weather to vanilla.")
            .define("localized", true);

    public static final ModConfigSpec.DoubleValue HAIL_DAMAGE = BUILDER
            .comment("Damage a hailstone does to a player or mob out under the sky with nothing on its head (1 = half",
                    "a heart). It ignores armour, and never takes anything below 1 health. Something worn on the",
                    "head stops it and loses 3 durability instead.")
            .defineInRange("hailDamage", 1.0, 0.0, 20.0);

    public static final ModConfigSpec.IntValue HAIL_INTERVAL = BUILDER
            .comment("Average ticks between hailstone hits on one player or mob while hail falls on it.")
            .defineInRange("hailInterval", 60, 20, 1200);

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

    public static double hailDamage() {
        return SPEC.isLoaded() ? HAIL_DAMAGE.get() : 1.0;
    }

    public static int hailInterval() {
        return SPEC.isLoaded() ? HAIL_INTERVAL.get() : 60;
    }

    public static AirMassSource airMassSource() {
        return SPEC.isLoaded() ? AIR_MASS_SOURCE.get() : AirMassSource.BANDS;
    }

    public static int bandPeriod() {
        return SPEC.isLoaded() ? BAND_PERIOD.get() : 64000;
    }

    public static double bandAmplitude() {
        return SPEC.isLoaded() ? BAND_AMPLITUDE.get() : 20.0;
    }

    public static double bandWeight() {
        return SPEC.isLoaded() ? BAND_WEIGHT.get() : 0.5;
    }

    public static double localBiomeWeight() {
        return SPEC.isLoaded() ? LOCAL_BIOME_WEIGHT.get() : 0.5;
    }

    /** The weather systems' settings. */
    public static dev.brights0ng.enginesandempires.weather.sim.SimParams simParams() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.enginesandempires.weather.sim.SimParams.DEFAULT;
        }
        return new dev.brights0ng.enginesandempires.weather.sim.SimParams(
                AIR_MASS_SOURCE.get() == AirMassSource.BANDS, BAND_PERIOD.get(), SYSTEM_SPEED.get(),
                SYSTEM_SPACING.get(), ZONE_RADIUS.get(), JET_CORE.get(), BLOCK_CHANCE.get());
    }

    /** The cloud spawner's settings. */
    public static dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSim.Settings cloudSettings() {
        dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSim.Settings d =
                dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSim.Settings.DEFAULT;
        if (!SPEC.isLoaded()) {
            return d;
        }
        return new dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSim.Settings(CLOUD_LAYER_SPACING.get(),
                CLOUD_LAYER_RADIUS.get(), CLOUD_HEAP_RADIUS.get(), CLOUD_MAX_PER_PLAYER.get(), d.spawnCover(),
                d.keepCover(), CLOUD_HEAP_DENSITY.get());
    }

    public static boolean cloudAudit() {
        return SPEC.isLoaded() && CLOUD_AUDIT.get();
    }

    private WeatherConfig() {
    }
}
