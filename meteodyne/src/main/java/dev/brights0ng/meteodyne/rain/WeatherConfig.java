package dev.brights0ng.meteodyne.rain;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Localized weather settings. A SERVER config ({@code serverconfig/meteodyne-weather-server.toml} in the world
 * folder); the values clients need (the height cooling) are sent to them with the weather sync.
 */
public final class WeatherConfig {

    public static final String FILE_NAME = "meteodyne-weather-server.toml";

    /** Defaults, also used before the config loads (and in tests). */
    /** Air cooling with height, C per 100 blocks (2026-10-15, Bright: 5, the pack's x0.2 scale of the standard 1). */
    public static final double DEFAULT_HEIGHT_COOLING_PER_100 = 5.0;
    public static final double DEFAULT_MAX_HEIGHT_COOLING = 30.0;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Air temperature.").push("temperature");
    }

    public static final ModConfigSpec.DoubleValue HEIGHT_COOLING_PER_100 = BUILDER
            .comment("How much colder the air gets 100 blocks above sea level, C (and warmer 100 blocks below, up to",
                    "14 C). 5 is the pack's x0.2 scale of the standard atmosphere (about 1 C per 100 m): 12 C colder",
                    "at y=300, 22 C colder at y=500. 0.65 is a block taken as a metre; 0 turns height cooling off.",
                    "Rain or snow, snow piling up, ice, ships high in the clouds and what people feel all follow it.")
            .defineInRange("heightCoolingPer100Blocks", DEFAULT_HEIGHT_COOLING_PER_100, 0.0, 20.0);

    public static final ModConfigSpec.DoubleValue MAX_HEIGHT_COOLING = BUILDER
            .comment("The most height can cool the air, in degrees C.")
            .defineInRange("maxHeightCooling", DEFAULT_MAX_HEIGHT_COOLING, 0.0, 100.0);

    /** Default highest y the world's mountains reach (Tectonic's 320; vanilla's is 256). */
    public static final int DEFAULT_MOUNTAIN_PEAK_Y = 320;

    public static final ModConfigSpec.IntValue MOUNTAIN_PEAK_Y = BUILDER
            .comment("The highest y the world's mountains reach (vanilla 256, Tectonic 320). Doesn't change terrain;",
                    "the weather stretches the world's mountains to real ones (peaks of about 4,000 m) when air is",
                    "pushed up a slope, so it cools and makes cloud, rain and snow as on real windward slopes.")
            .defineInRange("mountainPeakY", DEFAULT_MOUNTAIN_PEAK_Y, 96, 2032);

    static {
        BUILDER.comment("Underground (weather phase 8c). Under cover the air gives way to the rock's temperature: the",
                "surface's year-round mean down to bandTop, then a pull toward bandTemperature through the band, then",
                "one gradient to bedrockTemperature at the bottom of the world. Sky light lets outside air into cave",
                "mouths.").push("underground");
    }

    public static final ModConfigSpec.DoubleValue UG_EASE_DEPTH = BUILDER
            .comment("Blocks of cover over which the outside air (day and night, seasons, fronts) gives way to the",
                    "underground temperature.")
            .defineInRange("easeDepth", 20.0, 0.0, 128.0);

    public static final ModConfigSpec.DoubleValue UG_BAND_TOP = BUILDER
            .comment("y where the surface's year-round mean starts giving way to bandTemperature.")
            .defineInRange("bandTop", 40.0, -2032.0, 2032.0);

    public static final ModConfigSpec.DoubleValue UG_BAND_BOTTOM = BUILDER
            .comment("y where the rock is bandTemperature everywhere and the gradient to bedrock begins.")
            .defineInRange("bandBottom", 0.0, -2032.0, 2032.0);

    public static final ModConfigSpec.DoubleValue UG_BAND_TEMPERATURE = BUILDER
            .comment("The rock's temperature at bandBottom, C.")
            .defineInRange("bandTemperature", 21.0, -50.0, 100.0);

    public static final ModConfigSpec.DoubleValue UG_BEDROCK_TEMPERATURE = BUILDER
            .comment("The rock's temperature at the bottom of the world, C.")
            .defineInRange("bedrockTemperature", 45.0, -50.0, 150.0);

    public static final ModConfigSpec.DoubleValue UG_NOISE_AMPLITUDE = BUILDER
            .comment("Strength of the rock temperature's texture, C (in full below bandBottom, growing through the",
                    "band). 0 turns it off.")
            .defineInRange("noiseAmplitude", 3.0, 0.0, 20.0);

    public static final ModConfigSpec.DoubleValue UG_NOISE_SCALE = BUILDER
            .comment("Size of the texture's warmer and cooler pockets, blocks.")
            .defineInRange("noiseScale", 50.0, 4.0, 1024.0);

    public static final ModConfigSpec.DoubleValue UG_HUMIDITY = BUILDER
            .comment("Relative humidity (0-1) of the air under cover, the same everywhere, reached by the same",
                    "insulation as the temperature. It feeds the heat index (a starting value, to be tuned in play).")
            .defineInRange("humidity", 0.6, 0.0, 1.0);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> UG_CAVE_BIOMES = BUILDER
            .comment("Cave biome rules: biome;offset=C;useMin=true|false;min=C;useMax=true|false;max=C;humidity=0-1",
                    "offset is added to the underground temperature; the caps that are on hold the result anywhere",
                    "in that biome under cover; humidity (optional) replaces the flat underground humidity there.",
                    "Modded biomes work by id.")
            .defineListAllowEmpty("caveBiomes",
                    dev.brights0ng.meteodyne.climate.Underground.Settings.DEFAULT_CAVES,
                    () -> "minecraft:lush_caves;offset=0",
                    dev.brights0ng.meteodyne.climate.Underground.CaveRule::valid);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.comment("How the air feels (weather phase 8e-2): wind chill in cold wind, heat index in humid heat, and",
                "the warmth of direct sun. These move what Cold Sweat sees, not the air temperature rain, snow",
                "and ice follow.").push("apparent");
    }

    public static final ModConfigSpec.DoubleValue APPARENT_SCALE = BUILDER
            .comment("Multiplies wind chill and heat index (1 is the real formulas; 0 turns both off).")
            .defineInRange("scale", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.BooleanValue APPARENT_CAP_ENABLED = BUILDER
            .comment("Whether wind chill and heat index are each limited to 'cap' degrees.")
            .define("capEnabled", false);

    public static final ModConfigSpec.DoubleValue APPARENT_CAP = BUILDER
            .comment("The most wind chill can take off, and heat index put on, C, when 'capEnabled' is on.")
            .defineInRange("cap", 15.0, 0.0, 60.0);

    public static final ModConfigSpec.DoubleValue SUN_WARMTH_MAX = BUILDER
            .comment("How much warmer it feels in full sun at noon under a clear sky, C (the NWS's figure for the",
                    "heat index is up to 8). Smaller when the sun is low, a quarter behind glass or leaves, less",
                    "through cloud, none in shade or at night. 0 turns sun warmth off.")
            .defineInRange("sunWarmth", 8.0, 0.0, 30.0);

    public static final ModConfigSpec.IntValue SUN_BLOCK_REACH = BUILDER
            .comment("How far toward the sun, in blocks, blocks (and ships) can shade a person. Clouds have no limit.")
            .defineInRange("sunBlockReach", 96, 8, 512);

    static {
        BUILDER.pop();
    }

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
        BUILDER.pop().comment("Seasons: where in the year it is. A season provider sets it when one is registered (the pack",
                "registers Serene Seasons' calendar when it is installed); without one, the built-in calendar below does.")
                .push("seasons");
    }

    public static final ModConfigSpec.BooleanValue USE_BUILTIN_CALENDAR = BUILDER
            .comment("Without a season provider: whether the built-in calendar runs the year. Off: no seasons, every day",
                    "is the year's mean.")
            .define("useBuiltinCalendar", true);

    public static final ModConfigSpec.LongValue YEAR_TICKS = BUILDER
            .comment("The built-in calendar's year, ticks. The default is 72 days: 12 sub-seasons of 6 days, the shape of",
                    "Serene Seasons' default year. The year starts (early spring) at day time 0.")
            .defineInRange("yearTicks", dev.brights0ng.meteodyne.climate.BuiltinCalendar.DEFAULT_YEAR_TICKS, 24000L,
                    24000L * 10000);

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
                    "logs/weather-audit.log from the moment the world loads (also /meteodyne weather clouds audit on|off).")
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
        BUILDER.pop().comment("Lightning (weather phase 6b). A storm's strength is its cloud's lightning value, 0-1.")
                .push("lightning");
    }

    public static final ModConfigSpec.BooleanValue LIGHTNING = BUILDER
            .comment("Whether thunderstorms make lightning at all.")
            .define("enabled", true);

    public static final ModConfigSpec.DoubleValue FLASHES_PER_MINUTE = BUILDER
            .comment("Flashes a minute from a full-strength storm (weaker storms proportionally fewer).")
            .defineInRange("flashesPerMinute", 1.0, 0.0, 60.0);

    public static final ModConfigSpec.DoubleValue GROUND_SHARE = BUILDER
            .comment("The share of flashes that strike the ground; the rest stay in the cloud (but can hit ships and",
                    "flying things inside it).")
            .defineInRange("groundShare", 0.25, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue ATTACH_RADIUS = BUILDER
            .comment("How far (blocks) a ground strike looks around the spot under the flash for the tallest thing.")
            .defineInRange("attachRadius", 16.0, 0.0, 64.0);

    public static final ModConfigSpec.DoubleValue SKY_REACH = BUILDER
            .comment("How far (blocks) an in-cloud flash reaches to a ship or flying entity inside the cloud.")
            .defineInRange("skyReach", 24.0, 0.0, 128.0);

    public static final ModConfigSpec.DoubleValue FLASH_RANGE = BUILDER
            .comment("Storms flash only within this many blocks of a player, who are told of flashes this far.")
            .defineInRange("range", 3072.0, 256.0, 16384.0);

    static {
        BUILDER.pop().comment("Forecasting (weather phase 7). Drift: each weather system's slow random wander, which is",
                "what makes the live weather drift away from a forecast the further ahead it looks.").push("forecast");
    }

    public static final ModConfigSpec.DoubleValue DRIFT_SPEED = BUILDER
            .comment("How much a system's speed along its track wanders (standard deviation, share of its speed).")
            .defineInRange("driftSpeed", 0.10, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue DRIFT_CROSS = BUILDER
            .comment("How much a system wanders across its track (standard deviation, share of its speed).")
            .defineInRange("driftCross", 0.05, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue DRIFT_DEPTH = BUILDER
            .comment("How much a system's strength wanders (standard deviation, share of it; kept within 0.6x-1.4x).")
            .defineInRange("driftDepth", 0.15, 0.0, 0.4);

    public static final ModConfigSpec.IntValue DRIFT_CORRELATION = BUILDER
            .comment("How long a drift lasts before it has mostly relaxed away, ticks (24000 = one in-game day).",
                    "Longer means bigger forecast errors at long range.")
            .defineInRange("driftCorrelationTicks", 24000, 1000, 240000);

    public static final ModConfigSpec.IntValue FORECAST_DELAY = BUILDER
            .comment("Real seconds every forecast takes to arrive (the forecaster's thinking time; cached forecasts too).")
            .defineInRange("delaySeconds", 30, 0, 600);

    public static final ModConfigSpec.IntValue FORECAST_DAYS = BUILDER
            .comment("How many days the longer forecast covers (day 1 is tomorrow).")
            .defineInRange("days", 4, 1, 7);

    public static final ModConfigSpec.IntValue FORECAST_DAY_STEP = BUILDER
            .comment("Ticks per step of the day forecast's simulation (smaller is finer and slower).")
            .defineInRange("dayStepTicks", 250, 50, 2000);

    public static final ModConfigSpec.IntValue FORECAST_WEEK_STEP = BUILDER
            .comment("Ticks per step of the longer forecast's simulation.")
            .defineInRange("weekStepTicks", 1000, 100, 4000);

    public static final ModConfigSpec.IntValue FORECAST_DAY_CACHE = BUILDER
            .comment("In-game hours a day forecast is kept and reused for its area before a fresh one is made.")
            .defineInRange("dayCacheHours", 1, 1, 24);

    public static final ModConfigSpec.IntValue FORECAST_WEEK_CACHE = BUILDER
            .comment("In-game hours a longer forecast is kept and reused for its area.")
            .defineInRange("weekCacheHours", 6, 1, 48);

    public static final ModConfigSpec.IntValue FORECAST_QUEUE = BUILDER
            .comment("The most forecasts waiting to be worked out at once; more requests are turned away until there",
                    "is room.")
            .defineInRange("queue", 8, 1, 64);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.comment("The heat simulation (weather phase 8f): fires, furnaces, lava and coolers warming or cooling the",
                "air of rooms and of the open air close by, felt by players. Overworld only.").push("heat");
    }

    public static final ModConfigSpec.BooleanValue HEAT_ENABLED = BUILDER
            .comment("Whether the heat simulation runs.")
            .define("enabled", true);

    public static final ModConfigSpec.DoubleValue HEAT_BUDGET = BUILDER
            .comment("CPU cores the simulation may use on average (its own threads, not the server thread). When it",
                    "needs more, rooms are stepped less often in longer steps: warming stays on time, a little coarser.")
            .defineInRange("budgetCores", 0.25, 0.01, 4.0);

    public static final ModConfigSpec.IntValue HEAT_THREADS = BUILDER
            .comment("Threads the simulation runs on.")
            .defineInRange("threads", 1, 1, 4);

    public static final ModConfigSpec.DoubleValue HEAT_NEAR_STEP = BUILDER
            .comment("Seconds between steps of a region a player or ship is in or near.")
            .defineInRange("nearStepSeconds", 1.0, 0.25, 10.0);

    public static final ModConfigSpec.DoubleValue HEAT_FAR_STEP = BUILDER
            .comment("Seconds between steps of a region nobody is near (a loaded base elsewhere).")
            .defineInRange("farStepSeconds", 10.0, 1.0, 60.0);

    public static final ModConfigSpec.IntValue HEAT_NATURAL_REACH = BUILDER
            .comment("Natural sources (lava, magma, fire) take part only within this many blocks of a player or ship,",
                    "or inside a region a placed source keeps going.")
            .defineInRange("naturalReach", 32, 8, 128);

    public static final ModConfigSpec.IntValue HEAT_MARGIN = BUILDER
            .comment("Blocks of air and walls simulated around a group of sources.")
            .defineInRange("margin", 12, 4, 32);

    public static final ModConfigSpec.IntValue HEAT_MAX_SIZE = BUILDER
            .comment("The most blocks a region spans along any axis; bigger groups are split into several regions.")
            .defineInRange("maxSize", 48, 16, 96);

    static {
        BUILDER.pop();
    }

    /** The heat simulation's settings (defaults before the config loads). */
    public static dev.brights0ng.meteodyne.heat.world.HeatSettings heat() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.meteodyne.heat.world.HeatSettings.DEFAULT;
        }
        return new dev.brights0ng.meteodyne.heat.world.HeatSettings(HEAT_ENABLED.get(), HEAT_BUDGET.get(),
                HEAT_THREADS.get(), HEAT_NEAR_STEP.get(), HEAT_FAR_STEP.get(), HEAT_NATURAL_REACH.get(),
                HEAT_MARGIN.get(), HEAT_MAX_SIZE.get());
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The height cooling, C per 100 blocks. */
    public static double heightCoolingPer100() {
        return SPEC.isLoaded() ? HEIGHT_COOLING_PER_100.get() : DEFAULT_HEIGHT_COOLING_PER_100;
    }

    /** Wind chill and heat index: the multiplier and the optional cap. */
    public static dev.brights0ng.meteodyne.climate.Apparent.Settings apparent() {
        return SPEC.isLoaded()
                ? new dev.brights0ng.meteodyne.climate.Apparent.Settings(APPARENT_SCALE.get(),
                        APPARENT_CAP_ENABLED.get(), APPARENT_CAP.get())
                : dev.brights0ng.meteodyne.climate.Apparent.Settings.DEFAULT;
    }

    /** The most sun can warm a person, C (full sun, high sun, nothing in the way). */
    public static double sunWarmthMax() {
        return SPEC.isLoaded() ? SUN_WARMTH_MAX.get() : 8.0;
    }

    /** How far toward the sun blocks and ships can shade a person, blocks. */
    public static int sunBlockReach() {
        return SPEC.isLoaded() ? SUN_BLOCK_REACH.get() : 96;
    }

    /** The height cooling as a multiple of the standard atmosphere's (what {@code Temperature.heightCorrection} takes). */
    public static double heightCoolingScale() {
        return dev.brights0ng.meteodyne.climate.Temperature.scaleFor(heightCoolingPer100());
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

    /** The lightning settings now (defaults before the config loads). */
    public static dev.brights0ng.meteodyne.lightning.LightningModel.Settings lightning() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.meteodyne.lightning.LightningModel.Settings.DEFAULT;
        }
        return new dev.brights0ng.meteodyne.lightning.LightningModel.Settings(LIGHTNING.get(),
                FLASHES_PER_MINUTE.get(), GROUND_SHARE.get(), ATTACH_RADIUS.get(), SKY_REACH.get(), FLASH_RANGE.get());
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

    /** The built-in calendar (the default before the config loads). */
    public static dev.brights0ng.meteodyne.climate.BuiltinCalendar calendar() {
        return SPEC.isLoaded()
                ? new dev.brights0ng.meteodyne.climate.BuiltinCalendar(USE_BUILTIN_CALENDAR.get(), YEAR_TICKS.get())
                : dev.brights0ng.meteodyne.climate.BuiltinCalendar.DEFAULT;
    }

    /** The weather systems' settings. */
    public static dev.brights0ng.meteodyne.sim.SimParams simParams() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.meteodyne.sim.SimParams.DEFAULT;
        }
        return new dev.brights0ng.meteodyne.sim.SimParams(
                AIR_MASS_SOURCE.get() == AirMassSource.BANDS, BAND_PERIOD.get(), SYSTEM_SPEED.get(),
                SYSTEM_SPACING.get(), ZONE_RADIUS.get(), JET_CORE.get(), BLOCK_CHANCE.get());
    }

    /** How much weather systems drift (phase 7a). */
    public static dev.brights0ng.meteodyne.sim.Drift.Settings drift() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.meteodyne.sim.Drift.Settings.DEFAULT;
        }
        return new dev.brights0ng.meteodyne.sim.Drift.Settings(DRIFT_SPEED.get(), DRIFT_CROSS.get(),
                DRIFT_DEPTH.get(), DRIFT_CORRELATION.get());
    }

    /** The forecaster's settings (phase 7b). */
    public static dev.brights0ng.meteodyne.forecast.ForecastSettings forecast() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.meteodyne.forecast.ForecastSettings.DEFAULT;
        }
        return new dev.brights0ng.meteodyne.forecast.ForecastSettings(FORECAST_DELAY.get(),
                FORECAST_DAYS.get(), FORECAST_DAY_STEP.get(), FORECAST_WEEK_STEP.get(), FORECAST_DAY_CACHE.get(),
                FORECAST_WEEK_CACHE.get(), FORECAST_QUEUE.get());
    }

    /** The cloud spawner's settings. */
    public static dev.brights0ng.meteodyne.cloud.sim.CloudSim.Settings cloudSettings() {
        dev.brights0ng.meteodyne.cloud.sim.CloudSim.Settings d =
                dev.brights0ng.meteodyne.cloud.sim.CloudSim.Settings.DEFAULT;
        if (!SPEC.isLoaded()) {
            return d;
        }
        return new dev.brights0ng.meteodyne.cloud.sim.CloudSim.Settings(CLOUD_LAYER_SPACING.get(),
                CLOUD_LAYER_RADIUS.get(), CLOUD_HEAP_RADIUS.get(), CLOUD_MAX_PER_PLAYER.get(), d.spawnCover(),
                d.keepCover(), CLOUD_HEAP_DENSITY.get());
    }

    public static boolean cloudAudit() {
        return SPEC.isLoaded() && CLOUD_AUDIT.get();
    }

    /** The highest y the world's mountains reach (round 5 of the 8b follow-up). */
    public static int mountainPeakY() {
        return SPEC.isLoaded() ? MOUNTAIN_PEAK_Y.get() : DEFAULT_MOUNTAIN_PEAK_Y;
    }

    private static java.util.List<? extends String> cavesSeen;
    private static dev.brights0ng.meteodyne.climate.Underground.Settings undergroundCached;

    /** The underground temperature settings (phase 8c); cave entries re-parsed only when they change. */
    public static dev.brights0ng.meteodyne.climate.Underground.Settings underground() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.meteodyne.climate.Underground.Settings.DEFAULT;
        }
        java.util.List<? extends String> caves = UG_CAVE_BIOMES.get();
        var cached = undergroundCached;
        var settings = new dev.brights0ng.meteodyne.climate.Underground.Settings(UG_EASE_DEPTH.get(),
                UG_BAND_TOP.get(), UG_BAND_BOTTOM.get(), UG_BAND_TEMPERATURE.get(), UG_BEDROCK_TEMPERATURE.get(),
                UG_NOISE_AMPLITUDE.get(), UG_NOISE_SCALE.get(), UG_HUMIDITY.get(),
                cached != null && caves.equals(cavesSeen) ? cached.caves()
                        : dev.brights0ng.meteodyne.climate.Underground.Settings.caves(caves));
        cavesSeen = caves;
        undergroundCached = settings;
        return settings;
    }

    private WeatherConfig() {
    }
}
