package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
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

    private WeatherGameTests() {
    }
}
