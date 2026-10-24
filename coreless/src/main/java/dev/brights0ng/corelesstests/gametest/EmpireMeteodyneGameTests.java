// Written by Claude.
package dev.brights0ng.corelesstests.gametest;

import dev.brights0ng.corelesstests.CorelessTests;
import dev.brights0ng.empire.god.GodHunger;
import dev.brights0ng.meteodyne.climate.SeasonSource;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** With Meteodyne loaded, an Empire god's patience (one year by default) is Meteodyne's year. */
@GameTestHolder(CorelessTests.MODID)
@PrefixGameTestTemplate(false)
public final class EmpireMeteodyneGameTests {

    @GameTest(template = GameTestStructures.EMPTY)
    public static void aGodWaitsMeteodynesYear(GameTestHelper helper) {
        helper.assertTrue(WeatherConfig.SPEC.isLoaded(), "Meteodyne's config is loaded");
        ServerLevel level = helper.getLevel().getServer().overworld();
        boolean calendarBefore = WeatherConfig.USE_BUILTIN_CALENDAR.get();
        long yearBefore = WeatherConfig.YEAR_TICKS.get();
        try {
            // A year unlike the 72-day default, so the answer can only come from Meteodyne.
            WeatherConfig.USE_BUILTIN_CALENDAR.set(true);
            WeatherConfig.YEAR_TICKS.set(20 * 24000L);
            helper.assertTrue(SeasonSource.yearTicks(level) == 20 * 24000L,
                    "Meteodyne's year is 20 days, is " + SeasonSource.yearTicks(level));
            helper.assertTrue(GodHunger.limitTicks(level) == 20 * 24000L,
                    "a god waits Meteodyne's year, waits " + GodHunger.limitTicks(level));
        } finally {
            WeatherConfig.YEAR_TICKS.set(yearBefore);
            WeatherConfig.USE_BUILTIN_CALENDAR.set(calendarBefore);
        }
        helper.succeed();
    }

    private EmpireMeteodyneGameTests() {
    }
}
