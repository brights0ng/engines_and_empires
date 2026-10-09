package dev.brights0ng.enginesandempires.weather.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/** Phase 7a: systems' slow drift, and how a forecast run drifts away from the live one. */
class DriftTest {

    private static final SimParams P = SimParams.DEFAULT;
    private static final List<SystemsSim.Anchor> HERE = List.of(new SystemsSim.Anchor(0, 0));
    private static final long HOUR = 1000;
    private static final long DAY = 24_000;

    @Test
    void aDriftStateWandersAroundZeroWithUnitSpread() {
        SplittableRandom r = new SplittableRandom(3);
        double keep = Drift.keep(100, 24_000);
        double u = 0;
        double sum = 0;
        double sum2 = 0;
        int n = 400_000;
        for (int i = 0; i < n; i++) {
            u = Drift.step(u, keep, r.nextGaussian());
            sum += u;
            sum2 += u * u;
        }
        double mean = sum / n;
        double sd = Math.sqrt(sum2 / n - mean * mean);
        assertEquals(0, mean, 0.15, "centred on zero");
        assertEquals(1, sd, 0.15, "unit spread: " + sd);
    }

    @Test
    void aDriftIsRememberedForAboutItsCorrelationTime() {
        SplittableRandom r = new SplittableRandom(5);
        long dt = 100;
        long tau = 24_000;
        int lag = (int) (tau / dt);
        double keep = Drift.keep(dt, tau);
        int n = 600_000;
        double[] us = new double[n];
        double u = 0;
        for (int i = 0; i < n; i++) {
            u = Drift.step(u, keep, r.nextGaussian());
            us[i] = u;
        }
        double c = 0;
        double v = 0;
        for (int i = 0; i + lag < n; i++) {
            c += us[i] * us[i + lag];
            v += us[i] * us[i];
        }
        assertEquals(Math.exp(-1), c / v, 0.12, "after one correlation time about 1/e of it is left");
    }

    @Test
    void stepLengthDoesNotChangeTheSpread() {
        // The exact update: a day in 5 s steps and a day in in-game hours spread a drift the same.
        double a = spreadAfter(DAY, 100);
        double b = spreadAfter(DAY, HOUR);
        assertEquals(a, b, 0.05, "5 s steps " + a + " vs hourly " + b);
        assertEquals(Math.sqrt(1 - Math.exp(-2.0)), a, 0.05);
    }

    private static double spreadAfter(long total, long dt) {
        SplittableRandom r = new SplittableRandom(11);
        double keep = Drift.keep(dt, 24_000);
        double sum2 = 0;
        int runs = 20_000;
        for (int k = 0; k < runs; k++) {
            double u = 0;
            for (long t = 0; t < total; t += dt) {
                u = Drift.step(u, keep, r.nextGaussian());
            }
            sum2 += u * u;
        }
        return Math.sqrt(sum2 / runs);
    }

    @Test
    void factorsStayInTheirRanges() {
        assertEquals(1, Drift.speedFactor(0, 0.1), 1e-12);
        assertEquals(Drift.SPEED_MIN, Drift.speedFactor(-50, 0.1), 1e-12);
        assertEquals(Drift.DEPTH_MAX, Drift.depthFactor(50, 0.15), 1e-12);
        assertEquals(Drift.DEPTH_MIN, Drift.depthFactor(-50, 0.15), 1e-12);
        assertEquals(1.12, Drift.depthFactor(Drift.depthStateFor(1.12, 0.15), 0.15), 1e-12);
    }

    @Test
    void withoutDriftAForecastFollowsTheLiveRunExactly() {
        for (long seed : new long[]{7, 19}) {
            SystemsSim live = spunUp(seed, Drift.Settings.NONE);
            long start = 4 * DAY;
            long born = live.nextId();
            // A plain copy: no re-rolled newcomers either (their pushes on existing lows would differ).
            SystemsSim forecast = live.copy();
            Map<Long, double[]> l = new HashMap<>();
            Map<Long, double[]> f = new HashMap<>();
            run(live, forecast, start, 2 * DAY, new SplittableRandom(seed), l, f);
            for (Map.Entry<Long, double[]> e : f.entrySet()) {
                if (e.getKey() < born && l.containsKey(e.getKey())) {
                    double[] a = l.get(e.getKey());
                    assertEquals(a[0], e.getValue()[0], 1e-6, "the same place");
                    assertEquals(a[1], e.getValue()[1], 1e-6);
                }
            }
        }
    }

    @Test
    void forecastErrorGrowsWithRange() {
        double[] day1 = new double[2];
        double[] day4 = new double[2];
        for (long seed = 1; seed <= 6; seed++) {
            SystemsSim live = spunUp(seed, Drift.Settings.DEFAULT);
            long start = 4 * DAY;
            long born = live.nextId();
            SystemsSim forecast = live.forForecast(SimMath.hash(seed, start));
            Map<Long, double[]> l1 = new HashMap<>();
            Map<Long, double[]> f1 = new HashMap<>();
            SplittableRandom r = new SplittableRandom(seed * 31);
            run(live, forecast, start, DAY, r, l1, f1);
            add(day1, l1, f1, born);
            Map<Long, double[]> l4 = new HashMap<>();
            Map<Long, double[]> f4 = new HashMap<>();
            run(live, forecast, start + DAY, 3 * DAY, r, l4, f4);
            add(day4, l4, f4, born);
        }
        double e1 = day1[0] / day1[1];
        double e4 = day4[0] / day4[1];
        System.out.printf("Drift: mean position error %.0f blocks after 1 day, %.0f after 4 (%d / %d systems)%n", e1, e4,
                (int) day1[1], (int) day4[1]);
        assertTrue(e1 > 30 && e1 < 800, "day 1 off by a little: " + e1);
        assertTrue(e4 > 1.8 * e1, "day 4 much further off than day 1: " + e1 + " -> " + e4);
        assertTrue(e4 < 4000, "but not wildly: " + e4);
    }

    @Test
    void aForecastRollsSystemsThatFormDuringItItsOwnWay() {
        SystemsSim live = new SystemsSim(new JetStream(P, 42), 7, new ArrayList<>(), 0);
        SystemsSim forecast = live.forForecast(99);
        live.step(0, 100, 0, HERE, (x, z) -> true, null);
        forecast.step(0, 100, 0, HERE, (x, z) -> true, null);
        List<WeatherSystem> a = lows(live, 0);
        List<WeatherSystem> b = lows(forecast, 0);
        assertEquals(a.size(), b.size(), "lows form in the same places");
        int differ = 0;
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).x, b.get(i).x, 1e-9, "at the same place along the track");
            if (Math.abs(a.get(i).peak - b.get(i).peak) > 1e-6) {
                differ++;
            }
        }
        assertTrue(differ >= a.size() - 1, "but their make-up is rolled differently: " + differ + " of " + a.size());
        SystemsSim again = new SystemsSim(new JetStream(P, 42), 7, new ArrayList<>(), 0).forForecast(99);
        again.step(0, 100, 0, HERE, (x, z) -> true, null);
        assertEquals(b.get(0).peak, lows(again, 0).get(0).peak, 1e-12, "the same forecast rolls the same way");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static List<WeatherSystem> lows(SystemsSim sim, int track) {
        return sim.systems().stream().filter(s -> s.kind == WeatherSystem.Kind.LOW && s.track == track)
                .sorted((p, q) -> Double.compare(p.x, q.x)).toList();
    }

    /** A live run four in-game days in (hourly steps, with drift). */
    private static SystemsSim spunUp(long seed, Drift.Settings drift) {
        SystemsSim sim = new SystemsSim(new JetStream(P, seed), seed, new ArrayList<>(), 0);
        sim.setDrift(drift);
        SplittableRandom r = new SplittableRandom(seed);
        sim.step(0, HOUR, -0.3, HERE, (x, z) -> false, r);
        for (long t = HOUR; t <= 4 * DAY; t += HOUR) {
            sim.step(t, HOUR, -0.3, HERE, (x, z) -> true, r);
        }
        return sim;
    }

    /** Steps both for {@code span} ticks after {@code from}; records where each system ends up. */
    private static void run(SystemsSim live, SystemsSim forecast, long from, long span, SplittableRandom r,
                            Map<Long, double[]> l, Map<Long, double[]> f) {
        for (long t = from + HOUR; t <= from + span; t += HOUR) {
            live.step(t, HOUR, -0.3, HERE, (x, z) -> true, r);
            forecast.step(t, HOUR, -0.3, HERE, (x, z) -> true, null);
        }
        for (WeatherSystem s : live.systems()) {
            l.put(s.id, new double[]{s.x, s.z});
        }
        for (WeatherSystem s : forecast.systems()) {
            f.put(s.id, new double[]{s.x, s.z});
        }
    }

    private static void add(double[] acc, Map<Long, double[]> l, Map<Long, double[]> f, long born) {
        for (Map.Entry<Long, double[]> e : f.entrySet()) {
            double[] a = l.get(e.getKey());
            if (e.getKey() < born && a != null) {
                acc[0] += Math.hypot(a[0] - e.getValue()[0], a[1] - e.getValue()[1]);
                acc[1]++;
            }
        }
    }
}
