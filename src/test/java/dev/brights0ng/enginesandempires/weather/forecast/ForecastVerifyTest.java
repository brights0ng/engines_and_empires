package dev.brights0ng.enginesandempires.weather.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.FieldEnv;
import dev.brights0ng.enginesandempires.weather.field.FieldTile;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.sim.Drift;
import dev.brights0ng.enginesandempires.weather.sim.JetStream;
import dev.brights0ng.enginesandempires.weather.sim.SimMath;
import dev.brights0ng.enginesandempires.weather.sim.SimParams;
import dev.brights0ng.enginesandempires.weather.sim.SystemsSim;

/** Phase 7c: checking forecasts against the truth, and how forecast skill falls off with range on a made-up world. */
class ForecastVerifyTest {

    private static Forecast.Outlook outlook(double tMin, double tMax, double chance, Precip kind, double wind,
                                            double from) {
        return new Forecast.Outlook("x", 0, 6000, tMin, tMax, 0.5, chance, kind, 0.5, wind, from, wind * 1.3, 1010,
                1010, 0, EnumSet.noneOf(Forecast.Event.class));
    }

    private static Forecast forecast(Forecast.Outlook part) {
        return new Forecast(Forecast.Product.TODAY, 0, 0, 0, 0, List.of(part));
    }

    @Test
    void aPerfectForecastScoresPerfectly() {
        Forecast.Outlook o = outlook(5, 12, 0.8, Precip.RAIN, 6, 270);
        ForecastVerify.Tally t = new ForecastVerify.Tally();
        t.add(ForecastVerify.check(forecast(o), 0, o, 6));
        assertEquals(0, t.temperatureError(), 1e-9);
        assertEquals(0, t.brier(), 1e-9);
        assertEquals(1, t.hits);
        assertEquals(1, t.kindRight);
    }

    @Test
    void errorsAreCountedInFahrenheitAndBrier() {
        ForecastVerify.Tally t = new ForecastVerify.Tally();
        Forecast.Outlook f = outlook(5, 15, 0.7, Precip.RAIN, 6, 270);
        Forecast.Outlook seen = outlook(3, 13, 0.1, null, 8, 300);
        t.add(ForecastVerify.check(forecast(f), 0, seen, 6));
        assertEquals(3.6, t.temperatureError(), 1e-9, "2 C off on both ends is 3.6 F");
        assertEquals(0.36, t.brier(), 1e-9);
        assertEquals(1, t.falseAlarms);
        assertEquals(30, ForecastVerify.angle(270, 300), 1e-9);
        assertEquals(20, ForecastVerify.angle(350, 10), 1e-9, "across north");
    }

    @Test
    void skillComparesAgainstTheAverage() {
        ForecastVerify.Tally perfect = new ForecastVerify.Tally();
        ForecastVerify.Tally average = new ForecastVerify.Tally();
        double[] truth = {0, 1, 0, 1, 0.5, 0, 1, 0.2};
        for (double o : truth) {
            Forecast.Outlook seen = outlook(5, 10, o, o > 0 ? Precip.RAIN : null, 4, 270);
            perfect.add(ForecastVerify.check(forecast(seen), 0, seen, 6));
            average.add(ForecastVerify.check(forecast(outlook(5, 10, 0.4625, Precip.RAIN, 4, 270)), 0, seen, 6));
        }
        assertEquals(1, perfect.skill(), 1e-9);
        assertEquals(0, average.skill(), 1e-6);
    }

    @Test
    void aPeriodNeedsMostOfItsHoursObserved() {
        List<ForecastRun.Observation> hours = new ArrayList<>();
        ForecastChance.Point dry = new ForecastChance.Point(0.1, 0, 0, 0, 0, false, false, Precip.RAIN, 10);
        for (int h = 0; h < 4; h++) {
            hours.add(new ForecastRun.Observation(h * 1000L, new ForecastChance.Point[]{dry}, 3, 0, 1012));
        }
        assertNull(ForecastVerify.observed(hours, 0, 6000), "4 of 6 hours isn't enough");
        hours.add(new ForecastRun.Observation(4000, new ForecastChance.Point[]{dry}, 3, 0, 1012));
        Forecast.Outlook seen = ForecastVerify.observed(hours, 0, 6000);
        assertNotNull(seen, "5 of 6 is");
        assertEquals(10, seen.tMin(), 1e-9);
        assertEquals(0, seen.chance(), 1e-9);
    }

    // ---- the skill harness -----------------------------------------------------------------------------------------

    /** Sea west of x = -10000, land east of it; 12 C, fairly humid. */
    private static final class Climate implements FieldEnv {
        @Override
        public double[] climate(double x, double z) {
            return new double[]{12 - 0.0002 * z, 0.7, x < -10_000 ? 2 : 0};
        }

        @Override
        public double[] wind(double x, double z) {
            return new double[4];
        }

        @Override
        public double[] contact(double x, double z) {
            return new double[2];
        }

        @Override
        public double heightCorrection(double elevation) {
            return 0;
        }
    }

    /** A live world: systems with live drift, and a field stepped by them. Forecasts are made from copies of it. */
    private static final class Live {
        /** The forecast spot: a little south of storm track 0, where the lows' fronts sweep across. */
        static final double CZ = 3000;
        final long seed;
        final SystemsSim systems;
        final AtmosphereField field = new AtmosphereField();
        final SplittableRandom nudges;
        final List<SystemsSim.Anchor> here = List.of(new SystemsSim.Anchor(0, 0));
        long time;

        Live(long seed) {
            this.seed = seed;
            this.systems = new SystemsSim(new JetStream(SimParams.DEFAULT, seed), seed, new ArrayList<>(), 0);
            this.systems.setDrift(Drift.Settings.DEFAULT);
            this.nudges = new SplittableRandom(seed * 7 + 1);
            field.ensure(-8_000, 0, 18_000, new Climate(), 0);
            for (FieldTile tile : field.tiles().values()) {
                java.util.Arrays.fill(tile.elevation, 0f);
            }
            systems.step(0, 1000, -0.3, here, (x, z) -> false, nudges);
        }

        /** A snapshot of the live world as it stands (copies, so forecasts never touch it). */
        ForecastSnapshot snapshot(Forecast.Product product, double[] domain, long salt) {
            AtmosphereField copy = field.copyDomain(-domain[0], CZ - domain[2], domain[1], CZ + domain[2]);
            return new ForecastSnapshot(product, 0, CZ, seed, 63, systems.forForecast(salt, time), copy, time, time, 0.9,
                    0, ForecastSettings.DEFAULT.stepTicks(product), 4, 5, 30);
        }

        void hour() {
            long next = time + 1000;
            systems.step(next, 1000, -0.3, here, (x, z) -> true, nudges);
            FieldEnv env = ForecastRun.env(new ForecastSnapshot(Forecast.Product.WEEK, 0, 0, seed, 63, systems, field,
                    next, next, 0.9, 0, 1000, 4, 5, 30), systems.systems(), systems.jet(), next, -0.3);
            field.step(1000, env);
            time = next;
        }
    }

    @Test
    void forecastSkillFallsOffWithRange() {
        // Longer offline runs: -Deae.harnessDays=30 (and -Deae.harnessSeeds=3) on the test JVM.
        int days = Integer.getInteger("eae.harnessDays", 9);
        int seeds = Integer.getInteger("eae.harnessSeeds", 2);
        ForecastVerify.Tally[] today = new ForecastVerify.Tally[4];
        ForecastVerify.Tally[] week = new ForecastVerify.Tally[4];
        for (int i = 0; i < 4; i++) {
            today[i] = new ForecastVerify.Tally();
            week[i] = new ForecastVerify.Tally();
        }
        long started = System.nanoTime();
        for (long seed = 3; seed < 3 + 8L * seeds; seed += 8) {
            Live live = new Live(seed);
            // Two days to settle (dayTime = simulation time: day time 0 is 6:00).
            for (int h = 0; h < 48; h++) {
                live.hour();
            }
            List<ForecastRun.Observation> truth = new ArrayList<>();
            List<ForecastRun.Observation> truthWide = new ArrayList<>();
            List<Forecast> issued = new ArrayList<>();
            for (int h = 0; h < days * 24; h++) {
                long clock = ForecastRun.clock(live.time);
                long salt = SimMath.hash(seed, live.time);
                if (h < (days - 4) * 24 && clock % ForecastRun.PERIOD == 0) {
                    issued.add(ForecastRun.run(live.snapshot(Forecast.Product.TODAY,
                            ForecastSnapshot.domain(Forecast.Product.TODAY), salt)).at(0, Live.CZ, 0));
                }
                if (h < (days - 5) * 24 && clock == 0) {
                    issued.add(ForecastRun.run(live.snapshot(Forecast.Product.WEEK,
                            ForecastSnapshot.domain(Forecast.Product.WEEK), salt)).at(0, Live.CZ, 0));
                }
                // The truth through the point forecast's own lens: the readings around the spot.
                truth.add(ForecastRun.observeSpot(live.snapshot(Forecast.Product.TODAY,
                        ForecastSnapshot.truthDomain(Forecast.Product.TODAY), 1), live.time, 0, Live.CZ));
                truthWide.add(ForecastRun.observeSpot(live.snapshot(Forecast.Product.WEEK,
                        ForecastSnapshot.truthDomain(Forecast.Product.WEEK), 1), live.time, 0, Live.CZ));
                live.hour();
            }
            for (Forecast f : issued) {
                // Each product is checked against the truth over its own region.
                List<ForecastRun.Observation> seenHours = f.product() == Forecast.Product.TODAY ? truth : truthWide;
                for (int i = 0; i < f.parts().size(); i++) {
                    Forecast.Outlook p = f.parts().get(i);
                    Forecast.Outlook seen = ForecastVerify.observed(seenHours, p.start(), p.end());
                    if (seen == null) {
                        continue;
                    }
                    ForecastVerify.Check c = ForecastVerify.check(f, i, seen,
                            ForecastVerify.count(seenHours, p.start(), p.end()));
                    (f.product() == Forecast.Product.TODAY ? today : week)[i].add(c);
                }
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("Forecast skill harness (" + ms + " ms):");
        for (int i = 0; i < 4; i++) {
            System.out.println("  Day forecast, period " + (i + 1) + ": " + today[i].describe());
        }
        for (int i = 0; i < 4; i++) {
            System.out.println("  Longer forecast, day " + (i + 1) + ": " + week[i].describe());
        }
        assertTrue(today[0].count() > 10 && week[3].count() >= 4, "enough was checked");
        assertTrue(week[3].temperatureError() + week[3].brier() * 20 >= week[0].temperatureError()
                        + week[0].brier() * 20 - 0.5,
                "day 4 is no better than day 1 (allowing for noise)");
        assertTrue(today[0].temperatureError() < 6, "the first period is close on temperature: "
                + today[0].temperatureError());
    }
}
