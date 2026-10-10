package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.climate.Baseline;
import dev.brights0ng.enginesandempires.weather.climate.BiomeClimate;
import dev.brights0ng.enginesandempires.weather.climate.Climate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Phase 0 of the weather backbone ({@code claude/weather-backbone-plan.md}): the pack owns the Overworld's weather.
 * Vanilla's global rain is held clear, and {@code isRainingAt} answers from the pack's clouds (none yet), even when
 * vanilla's rain level says otherwise.
 *
 * <p>These change the level's global weather, so they run in a batch of their own and put it back.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class WeatherGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH, batch = "weather_global", timeoutTicks = 40)
    public static void vanillaRainIsHeldClear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setWeatherParameters(0, 6000, true, true);
        helper.runAfterDelay(2, () -> {
            helper.assertFalse(level.getLevelData().isRaining(), "vanilla's global rain should be held clear");
            helper.assertFalse(level.getLevelData().isThundering(), "vanilla's global thunder should be held clear");
            helper.succeed();
        });
    }

    @GameTest(template = SCRATCH, batch = "weather_global")
    public static void noRainWhereThereAreNoClouds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Well above the test room, under open sky.
        BlockPos open = helper.absolutePos(new BlockPos(3, 40, 3));
        float before = level.getRainLevel(1);
        level.setRainLevel(1);
        try {
            helper.assertTrue(level.isRaining(), "vanilla's rain level is up");
            helper.assertFalse(level.isRainingAt(open), "no cloud above, so no rain here, whatever vanilla's level says");
        } finally {
            level.setRainLevel(before);
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void biomeClimatesComeFromTheDataMap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var plains = Climate.climateOf(biomes.getHolderOrThrow(Biomes.PLAINS));
        var peaks = Climate.climateOf(biomes.getHolderOrThrow(Biomes.FROZEN_PEAKS));
        var grove = Climate.climateOf(biomes.getHolderOrThrow(Biomes.GROVE));
        var desert = Climate.climateOf(biomes.getHolderOrThrow(Biomes.DESERT));
        var jungle = Climate.climateOf(biomes.getHolderOrThrow(Biomes.JUNGLE));
        helper.assertTrue(plains.temperatureOffset() == 0 && !plains.frozen()
                && plains.surface() == BiomeClimate.Surface.LAND, "plains: no adjustment, from the data map: " + plains);
        helper.assertTrue(peaks.frozen() && grove.frozen(), "frozen peaks and groves never thaw: " + peaks + grove);
        helper.assertTrue(desert.overridesHumidity() && desert.humidity() < 0.2, "desert is dry: " + desert);
        helper.assertTrue(jungle.surface() == BiomeClimate.Surface.FOREST && jungle.temperatureOffset() > 0,
                "jungle: forest, a little hotter: " + jungle);
        // The climate itself comes from the world's noise.
        double[] n = Climate.noise(level, 0, 0);
        helper.assertTrue(n[0] >= -1.5 && n[0] <= 1.5 && n[1] >= -1.5 && n[1] <= 1.5, "noise in range: "
                + n[0] + ", " + n[1]);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void anAlwaysFrozenBiomeStaysBelowFreezing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        // Every biome with ice or powder snow that can't regrow, far down the warm side of the climate bands, at the
        // bottom of the world, with the world's noise as hot as it gets (+1): as warm as it gets.
        for (var key : java.util.List.of(Biomes.ICE_SPIKES, Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS,
                Biomes.SNOWY_SLOPES, Biomes.GROVE, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN)) {
            Holder<Biome> biome = biomes.getHolderOrThrow(key);
            BiomeClimate hot = dev.brights0ng.enginesandempires.weather.climate.NoiseClimate.resolve(1, 1,
                    Climate.climateOf(biome));
            Baseline.Sample s = Climate.sample(level, at.getX(), level.getMinBuildHeight(), 16000, hot);
            helper.assertTrue(s.temperature() <= BiomeClimate.FROZEN_MAX, key.location() + " held below freezing: "
                    + s.temperature());
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void theTemperatureServiceGivesAPlausibleValue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        double t = dev.brights0ng.enginesandempires.weather.climate.Temperature.at(level,
                helper.absolutePos(new BlockPos(3, 2, 3)));
        helper.assertTrue(Double.isFinite(t) && t > -60 && t < 60, "a plausible air temperature: " + t);
        helper.succeed();
    }

    private WeatherGameTests() {
    }

    @GameTest(template = SCRATCH, batch = "weather_systems")
    public static void theWorldHasWeatherSystemsAndWind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var sim = dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim.of(level);
        helper.assertTrue(sim != null, "the Overworld runs the weather systems");
        helper.assertTrue(!sim.systems().isEmpty(), "spun up with systems already in place");
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        var wind = dev.brights0ng.enginesandempires.weather.sim.world.Atmosphere.wind(level, at.getX(), at.getZ());
        helper.assertTrue(wind != null && Math.hypot(wind.aloftX(), wind.aloftZ()) > 0.5, "the jet blows aloft");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "weather_systems")
    public static void aSpawnedLowLowersThePressure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var sim = dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim.of(level);
        double x = 2_000_000;
        double z = 0;
        double before = dev.brights0ng.enginesandempires.weather.sim.world.Atmosphere.pressure(level, x, z);
        var low = sim.spawn(dev.brights0ng.enginesandempires.weather.sim.WeatherSystem.Kind.LOW, x, z);
        double after = dev.brights0ng.enginesandempires.weather.sim.world.Atmosphere.pressure(level, x, z);
        sim.systems().remove(low);
        helper.assertTrue(after < before - 10, "a low at its centre: " + before + " -> " + after);
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "weather_systems", timeoutTicks = 300)
    public static void theAtmosphereFieldStepsQuickly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var sim = dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim.of(level);
        // The field lives around players; the test server has none, so open a player-sized patch here.
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        sim.field().ensure(at.getX(), at.getZ(), 16000, sim.readEnv(), sim.time());
        helper.assertTrue(sim.field().covers(at.getX(), at.getZ()), "field tiles here");
        helper.runAfterDelay(210, () -> {
            double anomaly = sim.anomaly(at.getX(), at.getZ());
            helper.assertTrue(Double.isFinite(anomaly) && Math.abs(anomaly) < 30, "a sane air-mass anomaly: " + anomaly);
            helper.assertTrue(sim.lastStepMillis() < 40, "a live step stays cheap: " + sim.lastStepMillis() + " ms");
            helper.succeed();
        });
    }

    /** Phase 4a/4b: a cloud spawned over a spot is a server cloud and covers the sky there; set raining, it rains. */
    @GameTest(template = SCRATCH, batch = "weather_systems")
    public static void aSpawnedCloudCoversTheSkyAndRains(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var sim = dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim.of(level);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        sim.field().ensure(at.getX(), at.getZ(), 2000, sim.readEnv(), sim.time());
        var cloud = dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.spawn(level,
                dev.brights0ng.enginesandempires.weather.cloud.CloudType.NIMBOSTRATUS, at.getX(), at.getZ());
        helper.assertTrue(cloud != null, "a cloud was added");
        // Next tick, so the per-tick caches pick it up.
        helper.runAfterDelay(1, () -> {
            var shapes = dev.brights0ng.enginesandempires.weather.cloud.CloudSources.server(level);
            helper.assertTrue(shapes.stream().anyMatch(s -> s.regionId().equals(cloud.id)),
                    "the server's clouds include it");
            BlockPos below = helper.absolutePos(new BlockPos(3, 40, 3));
            var here = dev.brights0ng.enginesandempires.weather.rain.LocalWeather.at(level, below.getX() + 0.5,
                    below.getY(), below.getZ() + 0.5);
            helper.assertTrue(here.cover() > 0.3, "under the cloud: " + here.cover());
            // Whatever the air here, make it rain (and reach the ground).
            cloud.forcedRain = 1;
            cloud.precipitation = 1;
            cloud.rainBottom = Float.NEGATIVE_INFINITY;
            cloud.version++;
        });
        helper.runAfterDelay(3, () -> {
            try {
                BlockPos below = helper.absolutePos(new BlockPos(3, 40, 3));
                var here = dev.brights0ng.enginesandempires.weather.rain.LocalWeather.at(level, below.getX() + 0.5,
                        below.getY(), below.getZ() + 0.5);
                boolean dryBiome = !level.getBiome(below).value().hasPrecipitation();
                helper.assertTrue(here.falling() || dryBiome, "a raining nimbostratus rains on the ground under it");
                helper.assertTrue(level.isRainingAt(below) || here.snow() || dryBiome,
                        "and vanilla's rain check agrees");
            } finally {
                sim.cloudSim().clouds().remove(cloud);
            }
            helper.succeed();
        });
    }
}
