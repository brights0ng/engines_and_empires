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
    /**
     * These tests wait on real time (the forecaster's delay and its background thread), but the game-test server runs
     * ticks unthrottled (the whole suite takes about 10 s), so a tick budget means little: give them plenty.
     */
    private static final int REAL_TIME_TIMEOUT = 72_000;

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

    @GameTest(template = SCRATCH, batch = "weather_forecast", timeoutTicks = REAL_TIME_TIMEOUT)
    public static void forecastsAreCachedAndThrownAwayAfterAJump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO);
        AtomicReference<Forecast> first = new AtomicReference<>();
        AtomicReference<Forecast> second = new AtomicReference<>();
        AtomicReference<Forecast> third = new AtomicReference<>();
        ForecastService.setDelayForTests(0);
        // The cache window the first request falls in (unthrottled ticks can carry the clock past an in-game hour
        // while the first forecast is worked out; then the second rightly gets a fresh forecast, not the cached one).
        long window = Math.floorDiv(WeatherSim.of(level).time(), 1000L);
        helper.assertTrue(ForecastService.request(level, at.getX(), at.getZ(), Forecast.Product.TODAY,
                into(first, helper)), "the request is taken");
        boolean[] sameWindow = new boolean[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(first.get() != null, "the first forecast arrives"))
                .thenExecute(() -> {
                    helper.assertTrue(first.get().parts().size() == 4, "four periods");
                    sameWindow[0] = Math.floorDiv(WeatherSim.of(level).time(), 1000L) == window;
                    ForecastService.request(level, at.getX(), at.getZ(), Forecast.Product.TODAY, into(second, helper));
                    if (sameWindow[0]) {
                        helper.assertTrue(ForecastService.load()[1] == 0,
                                "the second is served from the cache: no work");
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(second.get() != null, "the second arrives"))
                .thenExecute(() -> {
                    if (sameWindow[0]) {
                        helper.assertTrue(ForecastText.lines(first.get()).equals(ForecastText.lines(second.get())),
                                "the same forecast for everyone in the area");
                    }
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

    @GameTest(template = SCRATCH, batch = "weather_forecast_delay", timeoutTicks = REAL_TIME_TIMEOUT)
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

    @GameTest(template = SCRATCH, batch = "weather_forecast_score", timeoutTicks = 400)
    public static void trackingScoresForecastsAcrossAStep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO);
        dev.brights0ng.enginesandempires.weather.forecast.ForecastScore.clear();
        dev.brights0ng.enginesandempires.weather.forecast.ForecastScore.track(level, at.getX(), at.getZ());
        // Two days: the first day forecast's periods and the first longer forecast's day 1 all end inside it.
        WeatherSim.of(level).advance(2 * 24_000);
        java.util.List<String> report = dev.brights0ng.enginesandempires.weather.forecast.ForecastScore.report();
        dev.brights0ng.enginesandempires.weather.forecast.ForecastScore.untrack(at.getX(), at.getZ());
        String all = String.join("\n", report);
        helper.assertTrue(all.contains("Day forecast, period 1: ") && !all.contains("period 1: nothing"),
                "day forecasts were checked:\n" + all);
        helper.assertTrue(all.contains("Longer forecast, day 1: "), "the longer forecast's day 1 was checked:\n" + all);
        EnginesAndEmpiresMod.LOGGER.info("Forecast score after a 2-day step:\n{}", all);
        helper.succeed();
    }

    private ForecastGameTests() {
    }
}
