package dev.brights0ng.meteodyne.gametest;

import java.util.List;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.SeasonSource;
import dev.brights0ng.meteodyne.forecast.Climatology;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8a: the climatology run in a running server. A two-day run moves the season override along with the
 * simulation, reads every hour at its spot, ends by itself, and gives the calendar back.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class ClimatologyGameTests {

    @GameTest(template = GameTestStructures.EMPTY, batch = "weather_climatology", timeoutTicks = 400)
    public static void aShortRunMovesTheSeasonReadsEveryHourAndCleansUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO);
        Climatology.clearSpots();
        Climatology.add(level, at.getX(), at.getZ());
        String error = Climatology.start(level, 2, null);
        helper.assertTrue(error == null, "the run starts: " + error);
        double from = SeasonSource.yearFraction(level);
        long yearTicks = SeasonSource.yearTicks(level);
        helper.assertTrue(SeasonSource.overridden(), "the season is overridden while it runs");

        helper.assertTrue(Climatology.stepHours(level, 24), "still going after one day");
        double moved = SeasonSource.yearFraction(level) - from;
        if (moved < 0) {
            moved += 1;
        }
        double expected = 24_000.0 / yearTicks;
        helper.assertTrue(Math.abs(moved - expected) < 1e-6,
                "the season moved a day on: " + moved + " (expected " + expected + ")");

        helper.assertFalse(Climatology.stepHours(level, 30), "it ends by itself after two days");
        helper.assertFalse(Climatology.running(), "not running");
        helper.assertFalse(SeasonSource.overridden(), "the calendar is given back");

        List<String> report = Climatology.report();
        String all = String.join("\n", report);
        helper.assertTrue(all.contains("48 hours read"), "every hour was read:\n" + all);
        helper.assertTrue(all.contains(" C (lows "), "with a season summary:\n" + all);
        Meteodyne.LOGGER.info("Climatology after a 2-day run:\n{}", all);
        Climatology.clearSpots();
        helper.succeed();
    }

    private ClimatologyGameTests() {
    }
}
