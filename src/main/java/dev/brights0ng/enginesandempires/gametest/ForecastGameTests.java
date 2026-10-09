package dev.brights0ng.enginesandempires.gametest;

import java.util.concurrent.atomic.AtomicReference;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.forecast.Forecast;
import dev.brights0ng.enginesandempires.weather.forecast.ForecastService;
import dev.brights0ng.enginesandempires.weather.forecast.ForecastText;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 7b: the forecaster in a running server. A forecast comes back (after the delay, not before), a second
 * request for the same area is answered from the cache without new work, and a jump in the weather
 * ({@code /eae weather step}) throws the cache away so the next answer is worked out afresh.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class ForecastGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    private static ForecastService.Callback into(AtomicReference<Forecast> out, GameTestHelper helper) {
        return new ForecastService.Callback() {
            @Override
            public void ready(Forecast forecast) {
                out.set(forecast);
            }

            @Override
            public void failed(String why) {
                helper.fail("the forecast failed: " + why);
            }
        };
    }

    @GameTest(template = SCRATCH, batch = "weather_forecast", timeoutTicks = 1200)
    public static void forecastsAreCachedAndThrownAwayAfterAJump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO);
        AtomicReference<Forecast> first = new AtomicReference<>();
        AtomicReference<Forecast> second = new AtomicReference<>();
        AtomicReference<Forecast> third = new AtomicReference<>();
        ForecastService.setDelayForTests(0);
        helper.assertTrue(ForecastService.request(level, at.getX(), at.getZ(), Forecast.Product.TODAY,
                into(first, helper)), "the request is taken");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(first.get() != null, "the first forecast arrives"))
                .thenExecute(() -> {
                    helper.assertTrue(first.get().parts().size() == 4, "four periods");
                    ForecastService.request(level, at.getX(), at.getZ(), Forecast.Product.TODAY, into(second, helper));
                    helper.assertTrue(ForecastService.load()[1] == 0, "the second is served from the cache: no work");
                })
                .thenWaitUntil(() -> helper.assertTrue(second.get() != null, "the second arrives"))
                .thenExecute(() -> {
                    helper.assertTrue(ForecastText.lines(first.get()).equals(ForecastText.lines(second.get())),
                            "the same forecast for everyone in the area");
                    ForecastService.request(level, at.getX(), at.getZ(), Forecast.Product.TODAY, into(third, helper));
                    WeatherSim.of(level).advance(1000);
                    helper.assertTrue(ForecastService.load()[1] >= 1, "after the jump it is worked out again");
                })
                .thenWaitUntil(() -> helper.assertTrue(third.get() != null, "the third arrives"))
                .thenExecute(() -> {
                    helper.assertTrue(third.get().issued() >= first.get().issued() + 1000,
                            "made from the weather after the jump: " + first.get().issued() + " -> "
                                    + third.get().issued());
                    ForecastService.setDelayForTests(-1);
                })
                .thenSucceed();
    }

    @GameTest(template = SCRATCH, batch = "weather_forecast_delay", timeoutTicks = 1200)
    public static void aForecastArrivesOnlyAfterTheDelay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO);
        AtomicReference<Forecast> got = new AtomicReference<>();
        long asked = System.currentTimeMillis();
        ForecastService.setDelayForTests(2);
        helper.assertTrue(ForecastService.request(level, at.getX() + 5000, at.getZ(), Forecast.Product.WEEK,
                into(got, helper)), "the request is taken");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(got.get() != null, "the forecast arrives"))
                .thenExecute(() -> {
                    long waited = System.currentTimeMillis() - asked;
                    helper.assertTrue(waited >= 1900, "not before the delay: " + waited + " ms");
                    helper.assertTrue(got.get().parts().size() >= 1, "with its days");
                    ForecastService.setDelayForTests(-1);
                })
                .thenSucceed();
    }

    private ForecastGameTests() {
    }
}
