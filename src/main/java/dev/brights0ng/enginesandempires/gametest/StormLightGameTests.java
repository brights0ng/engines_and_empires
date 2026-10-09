package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;
import dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import dev.brights0ng.enginesandempires.weather.sky.StormLight;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 6d: a storm darkens the gameplay light where it is. At noon under a spawned cumulonimbus the sky
 * darkening there is above the clear sky's, and a block's light (what spawning and undead see) is lower; far away
 * nothing changes.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class StormLightGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH, batch = "storm_light", timeoutTicks = 80)
    public static void aStormDarkensTheLightUnderIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WeatherSim sim = WeatherSim.of(level);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        long dayTime = level.getDayTime();
        level.setDayTime(dayTime - Math.floorMod(dayTime, 24000) + 6000);
        sim.field().ensure(at.getX(), at.getZ(), 2000, sim.readEnv(), sim.time());
        SimCloud cloud = CloudWorld.spawn(level, CloudType.CUMULONIMBUS_CAPILLATUS, at.getX(), at.getZ());
        helper.assertTrue(cloud != null, "a storm");
        helper.runAfterDelay(3, () -> {
            try {
                StormLight.invalidate(level);
                BlockPos sky = new BlockPos(at.getX(), level.getMaxBuildHeight() - 2, at.getZ());
                BlockPos far = sky.offset(6000, 0, 6000);
                double under = StormLight.darkness(level, sky.getX(), sky.getZ());
                double away = StormLight.darkness(level, far.getX(), far.getZ());
                helper.assertTrue(under > 0.6, "dark under the storm: " + under);
                helper.assertTrue(away < 0.05, "not far away: " + away);
                int clear = level.getSkyDarken();
                int stormy = StormLight.skyDarken(level, sky);
                helper.assertTrue(stormy > clear, "the sky darkening under it (" + stormy + ") beats clear (" + clear
                        + ")");
                helper.assertTrue(StormLight.skyDarken(level, far) == clear, "far away it is the clear sky's");
                helper.assertTrue(level.getMaxLocalRawBrightness(sky) < level.getMaxLocalRawBrightness(sky, clear),
                        "a block's light under it is lower: " + level.getMaxLocalRawBrightness(sky) + " vs "
                                + level.getMaxLocalRawBrightness(sky, clear));
            } finally {
                sim.cloudSim().clouds().remove(cloud);
                StormLight.invalidate(level);
                level.setDayTime(dayTime);
            }
            helper.succeed();
        });
    }

    private StormLightGameTests() {
    }
}
