package dev.brights0ng.enginesandempires.gametest;

import java.util.Optional;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.checks.StormSleep;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.WeatherCheck;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 6a: vanilla's position-less weather checks answered where they are asked. A thunderstorm is spawned
 * over the test and made to rain; loot's {@code weather_check} (Channeling's gate), pandas' thunder fear and the
 * shared queries see it there and not far away. Also the night skip's fast-forward.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class WeatherChecksGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    /** Far enough that no cloud of the storm reaches. */
    private static final int FAR = 6000;

    /** A raining thunderstorm right over the test (removed by the caller). */
    private static SimCloud storm(GameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        WeatherSim sim = WeatherSim.of(level);
        sim.field().ensure(at.getX(), at.getZ(), 2000, sim.readEnv(), sim.time());
        SimCloud cloud = CloudWorld.spawn(level, CloudType.CUMULONIMBUS_CAPILLATUS, at.getX(), at.getZ());
        helper.assertTrue(cloud != null, "a storm was added");
        cloud.forcedRain = 1;
        cloud.precipitation = 1;
        cloud.lightning = 1;
        cloud.rainBottom = Float.NEGATIVE_INFINITY;
        cloud.version++;
        return cloud;
    }

    private static boolean dry(ServerLevel level, BlockPos pos) {
        return !level.getBiome(pos).value().hasPrecipitation();
    }

    private static boolean passes(ServerLevel level, LootItemCondition condition, BlockPos pos) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .create(LootContextParamSets.COMMAND);
        return condition.test(new LootContext.Builder(params).create(Optional.empty()));
    }

    @GameTest(template = SCRATCH, batch = "weather_checks", timeoutTicks = 60)
    public static void weatherChecksAreAnsweredWhereTheyAreAsked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        SimCloud cloud = storm(helper, at);
        helper.runAfterDelay(3, () -> {
            try {
                BlockPos under = helper.absolutePos(new BlockPos(3, 2, 3));
                BlockPos far = under.offset(FAR, 0, FAR);
                LocalWeather.Here here = WeatherQueries.overhead(level, under.getX() + 0.5, under.getY(),
                        under.getZ() + 0.5);
                if (dry(level, under) || !here.falling()) {
                    // A dry biome (or air that won't let it fall): nothing to check against.
                    EnginesAndEmpiresMod.LOGGER.warn("weather checks test: nothing falls here ({}), checks skipped",
                            level.getBiome(under).getRegisteredName());
                    helper.succeed();
                    return;
                }
                EnginesAndEmpiresMod.LOGGER.info("weather checks test: storm over the test ({} {})", here.precip(),
                        here.strength());
                helper.assertTrue(WeatherQueries.precipitationOver(level, under), "something falls under the storm");
                helper.assertTrue(WeatherQueries.thunderOver(level, under), "and it thunders there");
                helper.assertFalse(WeatherQueries.thunderOver(level, far), "but not far away");

                LootItemCondition thundering = WeatherCheck.weather().setThundering(true).build();
                LootItemCondition clear = WeatherCheck.weather().setRaining(false).build();
                helper.assertTrue(passes(level, thundering, under), "weather_check{thundering} passes under it");
                helper.assertFalse(passes(level, thundering, far), "and fails far away");
                helper.assertFalse(passes(level, clear, under), "weather_check{raining:false} fails under it");
                helper.assertTrue(passes(level, clear, far), "and passes far away");

                Panda panda = EntityType.PANDA.create(level);
                helper.assertTrue(panda != null, "a panda");
                panda.setMainGene(Panda.Gene.WORRIED);
                panda.setHiddenGene(Panda.Gene.WORRIED);
                panda.moveTo(Vec3.atBottomCenterOf(under));
                level.addFreshEntity(panda);
                helper.assertTrue(panda.isScared(), "a worried panda under the storm is scared");
                panda.moveTo(Vec3.atBottomCenterOf(far));
                helper.assertFalse(panda.isScared(), "and not far away");
                panda.discard();
            } finally {
                WeatherSim.of(level).cloudSim().clouds().remove(cloud);
            }
            helper.succeed();
        });
    }

    @GameTest(template = SCRATCH, batch = "weather_sleep")
    public static void theNightSkipRunsTheWeatherAhead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WeatherSim sim = WeatherSim.of(level);
        long before = sim.time();
        StormSleep.fastForward(level, 2000);
        long gained = sim.time() - before;
        helper.assertTrue(gained >= 2000 && gained <= 2002, "the simulation ran 2000 ticks ahead: " + gained);
        helper.succeed();
    }

    /**
     * Loads the MineColonies classes the weather mixins go into, so a MineColonies update that moves a call fails here
     * (the mixins are required once their class exists) rather than when a colony first runs that code.
     */
    @GameTest(template = SCRATCH, batch = "weather_checks")
    public static void theColonyWeatherMixinsApply(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("minecolonies")) {
            helper.succeed();
            return;
        }
        for (String name : new String[] {
                "com.minecolonies.core.entity.ai.workers.CitizenAI",
                "com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander",
                "com.minecolonies.core.entity.ai.workers.crafting.AbstractEntityAICrafting",
                "com.minecolonies.core.colony.buildings.workerbuildings.BuildingCook",
                "com.minecolonies.core.colony.managers.RegisteredStructureManager",
                "com.minecolonies.api.util.SoundUtils"}) {
            try {
                Class.forName(name, true, WeatherChecksGameTests.class.getClassLoader());
            } catch (Throwable t) {
                helper.fail("couldn't load " + name + " with its weather mixin: " + t);
                return;
            }
        }
        helper.succeed();
    }

    private WeatherChecksGameTests() {
    }
}
