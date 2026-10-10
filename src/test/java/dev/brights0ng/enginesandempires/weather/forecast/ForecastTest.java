package dev.brights0ng.enginesandempires.weather.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudDiagnostics;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.FieldEnv;
import dev.brights0ng.enginesandempires.weather.field.FieldTile;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.sim.JetStream;
import dev.brights0ng.enginesandempires.weather.sim.SimParams;
import dev.brights0ng.enginesandempires.weather.sim.SystemsSim;

/** Phase 7b: the forecast's chance maths, its text, and whole runs on a made-up world. */
class ForecastTest {

    // ---- text ------------------------------------------------------------------------------------------------------

    @Test
    void temperaturesReadInFahrenheit() {
        assertEquals(32, ForecastText.fahrenheit(0));
        assertEquals(212, ForecastText.fahrenheit(100));
        assertEquals(-40, ForecastText.fahrenheit(-40));
        assertEquals(68, ForecastText.fahrenheit(20));
    }

    @Test
    void windWordsFollowTheScale() {
        assertEquals("calm", ForecastText.windWord(0.4));
        assertEquals("light", ForecastText.windWord(0.5));
        assertEquals("light", ForecastText.windWord(3.3));
        assertEquals("breezy", ForecastText.windWord(3.4));
        assertEquals("windy", ForecastText.windWord(8));
        assertEquals("strong", ForecastText.windWord(14));
        assertEquals("gale", ForecastText.windWord(21));
        assertEquals("storm", ForecastText.windWord(28.5));
    }

    @Test
    void windDirectionsAreWhereItBlowsFrom() {
        assertEquals("W", ForecastText.compass(ForecastChance.fromDegrees(1, 0)), "blowing east: a west wind");
        assertEquals("N", ForecastText.compass(ForecastChance.fromDegrees(0, 1)), "blowing south (+z): a north wind");
        assertEquals("S", ForecastText.compass(ForecastChance.fromDegrees(0, -1)));
        assertEquals("SW", ForecastText.compass(ForecastChance.fromDegrees(1, -1)));
    }

    // ---- chance ----------------------------------------------------------------------------------------------------

    private static CloudDiagnostics.Need need(double nimbostratus, CloudType heap, double heapCover, double rh) {
        double[] layer = new double[CloudType.values().length];
        layer[CloudType.NIMBOSTRATUS.ordinal()] = nimbostratus;
        double[] hint = new double[CloudType.values().length];
        return new CloudDiagnostics.Need(layer, hint, heap, heapCover, 200, rh, 0.5, 0, Double.NaN, 0, "none", 0, 0, 0);
    }

    @Test
    void aFullNimbostratusDeckRainsOnMostOfTheSpot() {
        ForecastChance.Point p = ForecastChance.point(need(1, null, 0, 0.9), 12, 0, 0, 0.8);
        assertTrue(p.layerWet() > 0.9, "under the sheet: " + p.layerWet());
        assertTrue(p.layerStrength() > 0.4, "steady rain: " + p.layerStrength());
        assertEquals(Precip.RAIN, p.kind());
        assertTrue(p.cover() > 0.95);
    }

    @Test
    void aClearSkyIsDry() {
        ForecastChance.Point p = ForecastChance.point(need(0, null, 0, 0.4), 12, 0, 0, 0.5);
        assertEquals(0, p.wet(), 1e-12);
        assertTrue(p.cover() < 0.05);
    }

    @Test
    void halfAPeriodUnderASheetReadsLikely() {
        ForecastChance.Bucket b = new ForecastChance.Bucket(1);
        ForecastChance.Point wet = ForecastChance.point(need(1, null, 0, 0.9), 12, 0, 0, 0.8);
        ForecastChance.Point dry = ForecastChance.point(need(0, null, 0, 0.4), 12, 0, 0, 0.8);
        for (int i = 0; i < 12; i++) {
            b.add(new ForecastChance.Point[]{i < 6 ? wet : dry}, 3, 0, 1005, 0.5);
        }
        Forecast.Outlook o = b.finish("x", 0, 6000);
        assertTrue(o.chance() > 0.85, "a sheet for half the period is likely, not 50%: " + o.chance());
        assertEquals(Precip.RAIN, o.kind());
    }

    @Test
    void scatteredStormsGiveAPartialChanceThatGrowsWithTime() {
        ForecastChance.Point storms = ForecastChance.point(need(0, CloudType.CUMULONIMBUS_CALVUS, 0.3, 0.8), 25, 0,
                0, 0.8);
        assertTrue(storms.heapWet() > 0 && storms.heapWet() < 0.3, "showers cover part of a spot: " + storms.heapWet());
        assertTrue(storms.thunder());
        ForecastChance.Bucket hour = new ForecastChance.Bucket(1);
        ForecastChance.Bucket six = new ForecastChance.Bucket(1);
        hour.add(new ForecastChance.Point[]{storms}, 4, 0, 1008, 1);
        for (int i = 0; i < 6; i++) {
            six.add(new ForecastChance.Point[]{storms}, 4, 0, 1008, 1);
        }
        double c1 = hour.finish("x", 0, 1000).chance();
        double c6 = six.finish("x", 0, 6000).chance();
        assertTrue(c1 > 0.02 && c1 < 0.5, "an hour of scattered storms: " + c1);
        assertTrue(c6 > c1 && c6 < 0.9, "six hours: more likely, still not certain: " + c6);
        assertTrue(six.finish("x", 0, 6000).events().contains(Forecast.Event.THUNDERSTORM));
    }

    @Test
    void aNegligibleChanceNamesNoKind() {
        ForecastChance.Bucket b = new ForecastChance.Bucket(1);
        b.add(new ForecastChance.Point[]{ForecastChance.point(need(0, null, 0, 0.4), 12, 0, 0, 0.5)}, 0, 0, 1013, 1);
        Forecast.Outlook o = b.finish("x", 0, 1000);
        assertNull(o.kind());
        assertTrue(ForecastText.precipitation(o).startsWith("Dry"));
    }

    // ---- whole runs ------------------------------------------------------------------------------------------------

    /** A world with land east of x = -6000 and sea west of it, 12 C, fairly humid. */
    private static final class World implements FieldEnv {
        @Override
        public double[] climate(double x, double z) {
            return new double[]{12, 0.7, x < -6000 ? 2 : 0};
        }

        @Override
        public double[] wind(double x, double z) {
            return new double[]{4, 0, 8, 0};
        }

        @Override
        public double[] contact(double x, double z) {
            return new double[]{0, 0};
        }

        @Override
        public double heightCorrection(double elevation) {
            return -0.0325 * elevation;
        }
    }

    private static AtmosphereField field() {
        AtmosphereField f = new AtmosphereField();
        f.ensure(0, 0, 16_000, new World(), 0);
        for (FieldTile tile : f.tiles().values()) {
            java.util.Arrays.fill(tile.elevation, 0f);
        }
        return f;
    }

    private static SystemsSim systems(long seed) {
        SystemsSim sim = new SystemsSim(new JetStream(SimParams.DEFAULT, seed), seed, new ArrayList<>(), 0);
        List<SystemsSim.Anchor> here = List.of(new SystemsSim.Anchor(0, 0));
        SplittableRandom r = new SplittableRandom(seed);
        sim.step(0, 1000, -0.3, here, (x, z) -> false, r);
        for (long t = 1000; t <= 48_000; t += 1000) {
            sim.step(t, 1000, -0.3, here, (x, z) -> true, r);
        }
        return sim;
    }

    private static ForecastSnapshot snapshot(Forecast.Product product, long seed) {
        SystemsSim live = systems(seed);
        AtmosphereField f = field();
        double[] d = ForecastSnapshot.domain(product);
        AtmosphereField copy = f.copyDomain(-d[0], -d[2], d[1], d[2]);
        long stepTicks = ForecastSettings.DEFAULT.stepTicks(product);
        // Day time 3000 (9:00): in the morning period.
        return new ForecastSnapshot(product, 0, 0, seed, 63, live.forForecast(99), copy, 48_000, 3000, 0.9, 96 * 24000,
                stepTicks, 4, 5, 30);
    }

    @Test
    void theDayForecastHasFourPeriodsFromNow() {
        long started = System.nanoTime();
        Forecast f = ForecastRun.run(snapshot(Forecast.Product.TODAY, 5)).at(0, 0, 0);
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertNotNull(f);
        assertEquals(4, f.parts().size());
        assertEquals("Morning (6am-12pm)", f.parts().get(0).label());
        assertEquals("Afternoon (12pm-6pm)", f.parts().get(1).label());
        assertEquals("Night (12am-6am) tomorrow", f.parts().get(3).label());
        assertEquals(48_000, f.parts().get(0).start(), "the current period starts now");
        assertEquals(48_000 + 3000, f.parts().get(0).end(), "and ends at noon");
        for (Forecast.Outlook o : f.parts()) {
            assertTrue(o.tMin() <= o.tMax());
            assertTrue(o.tMin() > -30 && o.tMax() < 45, "a sensible temperature: " + o.tMin() + ".." + o.tMax());
            assertTrue(o.chance() >= 0 && o.chance() <= 1);
            assertTrue(o.windSpeed() >= 0);
        }
        System.out.println("Forecast (today) took " + ms + " ms:");
        ForecastText.lines(f).forEach(l -> System.out.println("  " + l));
        assertTrue(ms < 20_000, "well inside the forecaster's 30 s: " + ms);
    }

    @Test
    void theLongerForecastCoversWholeDaysFromTomorrow() {
        long started = System.nanoTime();
        Forecast f = ForecastRun.run(snapshot(Forecast.Product.WEEK, 5)).at(0, 0, 0);
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertNotNull(f);
        assertEquals(4, f.parts().size());
        Forecast.Outlook day1 = f.parts().get(0);
        assertEquals(48_000 + 15_000, day1.start(), "day 1 starts at the next midnight (15 hours after 9:00)");
        assertEquals(24_000, day1.end() - day1.start());
        assertTrue(day1.label().startsWith("Tomorrow"));
        System.out.println("Forecast (week) took " + ms + " ms:");
        ForecastText.lines(f).forEach(l -> System.out.println("  " + l));
        assertTrue(ms < 20_000, "well inside the forecaster's 30 s: " + ms);
    }

    @Test
    void theSameSnapshotGivesTheSameForecast() {
        Forecast a = ForecastRun.run(snapshot(Forecast.Product.TODAY, 9)).at(0, 0, 0);
        Forecast b = ForecastRun.run(snapshot(Forecast.Product.TODAY, 9)).at(0, 0, 0);
        assertEquals(ForecastText.lines(a), ForecastText.lines(b));
    }

    // ---- point forecasts (7d) --------------------------------------------------------------------------------------

    @Test
    void aSpotsOffsetShiftsItsTemperatures() {
        ForecastGrid grid = ForecastRun.run(snapshot(Forecast.Product.TODAY, 5));
        Forecast plain = grid.at(0, 0, 0);
        Forecast warmer = grid.at(0, 0, 4);
        for (int i = 0; i < plain.parts().size(); i++) {
            assertEquals(plain.parts().get(i).tMax() + 4, warmer.parts().get(i).tMax(), 1e-9);
            assertEquals(plain.parts().get(i).tMin() + 4, warmer.parts().get(i).tMin(), 1e-9);
            assertEquals(plain.parts().get(i).chance(), warmer.parts().get(i).chance(), 1e-9, "rain doesn't move");
        }
    }

    @Test
    void aColdSpotSnowsUnderTheSameCloudThatRainsBelow() {
        ForecastChance.Point rain = ForecastChance.point(need(1, null, 0, 0.9), 6, 0, 0, 0.8);
        assertEquals(Precip.RAIN, rain.kind());
        ForecastChance.Point top = rain.shifted(-8);
        assertEquals(Precip.SNOW, top.kind(), "8 C colder (a mountain top): snow");
        assertEquals(rain.wet(), top.wet(), 1e-12, "as much falls");
        assertEquals(-2, top.groundT(), 1e-12);
    }

    @Test
    void aSpotReadsTheReadingsAroundIt() {
        Forecast.Product p = Forecast.Product.TODAY;
        int n = ForecastGrid.side(p);
        assertEquals(7, n, "512 blocks at 128 apart, and a margin");
        int[] centre = ForecastGrid.neighbourhood(p, 0, 0, 0, 0);
        assertEquals(n * n / 2, centre[4], "the centre spot reads around the middle reading");
        double[] mid = ForecastGrid.spot(p, 0, 0, centre[4]);
        assertEquals(0, mid[0], 1e-9);
        assertEquals(0, mid[1], 1e-9);
        int[] corner = ForecastGrid.neighbourhood(p, 0, 0, 255, -255);
        double[] near = ForecastGrid.spot(p, 0, 0, corner[4]);
        assertEquals(256, near[0], 1e-9, "a corner spot reads around the reading nearest it");
        assertEquals(-256, near[1], 1e-9);
        int[] beyond = ForecastGrid.neighbourhood(p, 0, 0, 5000, 5000);
        for (int idx : beyond) {
            assertTrue(idx >= 0 && idx < n * n, "kept inside the grid");
        }
    }

    @Test
    void aDomainCopyLeavesTheLiveFieldAlone() {
        AtmosphereField live = field();
        float before = live.tiles().values().iterator().next().t[0];
        AtmosphereField copy = live.copyDomain(-12_000, -6_000, 4_000, 6_000);
        assertTrue(copy.tiles().size() > 0 && copy.tiles().size() < live.tiles().size());
        copy.step(1000, new World());
        copy.tiles().values().forEach(t -> java.util.Arrays.fill(t.t, 99f));
        assertEquals(before, live.tiles().values().iterator().next().t[0], 0, "the live tiles are untouched");
    }
}
