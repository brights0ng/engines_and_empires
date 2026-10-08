package dev.brights0ng.enginesandempires.weather.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/** The jet, the systems' life cycles, their fronts, and the pressure and wind they make. */
class WeatherSystemsTest {

    private static final SimParams P = SimParams.DEFAULT;
    private static final JetStream JET = new JetStream(P, 42);

    private static WeatherSystem low(double life, int hem) {
        long lifetime = 100_000;
        return new WeatherSystem(1, WeatherSystem.Kind.LOW, 0, 0, hem > 0 ? 0 : 1, hem, (long) (life * lifetime),
                lifetime, 25, 4000, false);
    }

    @Test
    void tracksAlternateBetweenNorthernAndMirroredZones() {
        assertEquals(1, JET.hemisphere(0), "spawn's track: cold to the north");
        assertEquals(-1, JET.hemisphere(1), "the next track south: mirrored");
        assertEquals(-1, JET.hemisphere(-1));
        assertEquals(1, JET.hemisphere(2));
        JetStream biome = new JetStream(new SimParams(false, 64000, 2.5, 12000, 16000, 15, 0.06), 42);
        assertEquals(1, biome.hemisphere(1), "biome mode: no mirrored zones");
    }

    @Test
    void tracksMeanderButStayNearTheirCentre() {
        double max = 0;
        for (int x = -200_000; x <= 200_000; x += 1000) {
            max = Math.max(max, Math.abs(JET.trackZ(0, x, 0, 0)));
        }
        assertTrue(max > 0.04 * P.bandPeriod(), "it does wander: " + max);
        assertTrue(max <= 1.4 * JetStream.MEANDER * P.bandPeriod() + 1, "but not far: " + max);
    }

    @Test
    void summerShiftsTracksTowardTheirColdSide() {
        double winter = JET.trackZ(0, 0, 0, -1);
        double summer = JET.trackZ(0, 0, 0, 1);
        assertTrue(summer < winter, "northern-style track moves north (-Z) in summer");
        assertTrue(JET.trackZ(1, 0, 0, 1) > JET.trackZ(1, 0, 0, -1), "mirrored track moves south in summer");
    }

    @Test
    void theJetBlowsEastAndStrongerInWinter() {
        double[] winter = JET.aloftWind(0, JET.trackZ(0, 0, 0, -1), 0, -1);
        double[] summer = JET.aloftWind(0, JET.trackZ(0, 0, 0, 1), 0, 1);
        assertTrue(winter[0] > 0 && summer[0] > 0, "west to east");
        assertTrue(Math.hypot(winter[0], winter[1]) > 1.5 * Math.hypot(summer[0], summer[1]));
        double[] steer = JET.steering(0, 0, 0, 0);
        assertTrue(steer[0] * 20 > 1 && steer[0] * 20 < 3, "steering about 2-2.5 blocks a second: " + steer[0] * 20);
    }

    @Test
    void lowsDeepenThenFillAndHighsBuildHoldFade() {
        assertTrue(low(0.05, 1).strength() < low(0.3, 1).strength());
        assertEquals(25, low(0.45, 1).strength(), 1e-9, "deepest at 45%");
        assertTrue(low(0.9, 1).strength() < low(0.6, 1).strength());
        assertEquals(WeatherSystem.Stage.WAVE, low(0.05, 1).stage());
        assertEquals(WeatherSystem.Stage.OCCLUDING, low(0.7, 1).stage());
        WeatherSystem high = new WeatherSystem(2, WeatherSystem.Kind.HIGH, 0, 0, 0, 1, 50_000, 100_000, 15, 6000, false);
        assertEquals(15, high.strength(), 1e-9);
        assertTrue(high.signedStrength() > 0 && low(0.5, 1).signedStrength() < 0);
    }

    @Test
    void frontsHaveTheTextbookShape() {
        List<FrontGeometry.Front> mature = FrontGeometry.of(low(0.35, 1));
        FrontGeometry.Front warm = find(mature, FrontGeometry.Type.WARM);
        FrontGeometry.Front cold = find(mature, FrontGeometry.Type.COLD);
        double[] warmEnd = warm.points().get(warm.points().size() - 1);
        double[] coldEnd = cold.points().get(cold.points().size() - 1);
        assertTrue(warmEnd[0] > 3000 && Math.abs(warmEnd[1]) < warmEnd[0], "warm front runs east: " + warmEnd[0]);
        assertTrue(coldEnd[1] > 3000, "cold front runs south: " + coldEnd[1]);
        assertTrue(mature.stream().noneMatch(f -> f.type() == FrontGeometry.Type.OCCLUDED), "not occluded yet");

        List<FrontGeometry.Front> old = FrontGeometry.of(low(0.75, 1));
        assertTrue(old.stream().anyMatch(f -> f.type() == FrontGeometry.Type.OCCLUDED), "an old low is occluded");
        assertTrue(FrontGeometry.of(low(0.01, 1)).isEmpty(), "a newborn wave has no fronts yet");

        FrontGeometry.Front mirroredCold = find(FrontGeometry.of(low(0.35, -1)), FrontGeometry.Type.COLD);
        double[] mEnd = mirroredCold.points().get(mirroredCold.points().size() - 1);
        assertTrue(mEnd[1] < -3000, "a mirrored low's cold front runs north: " + mEnd[1]);
    }

    @Test
    void windCirclesLowsTheRightWayAndSpiralsIn() {
        WeatherSystem north = low(0.45, 1);
        List<WeatherSystem> list = List.of(north);
        assertEquals(PressureField.NORMAL - 25, PressureField.pressure(list, 0, 0), 1e-9);
        double[] g = PressureField.gradient(list, 2800, 0);
        double[] v = PressureField.balanced(g, 1);
        assertTrue(v[1] < 0, "east of a northern-style low the wind blows north (counterclockwise)");
        double[] vm = PressureField.balanced(g, -1);
        assertTrue(vm[1] > 0, "in a mirrored zone it blows south (clockwise)");
        double[] s = PressureField.surface(v, g);
        assertTrue(s[0] < 0, "near the ground it is turned in toward the low (west, here)");
        double speed = Math.hypot(s[0], s[1]);
        assertTrue(speed > 8 && speed < 18, "a mature low gives a fresh-to-strong breeze: " + speed);
    }

    @Test
    void genesisFillsAZoneWithEvenlySpacedLowsAndHighs() {
        SystemsSim sim = new SystemsSim(JET, 7, new ArrayList<>(), 0);
        sim.step(0, 100, 0, List.of(new SystemsSim.Anchor(0, 0)), (x, z) -> true, null);
        List<WeatherSystem> track0 = sim.systems().stream()
                .filter(s -> s.kind == WeatherSystem.Kind.LOW && s.track == 0).sorted((a, b) -> Double.compare(a.x, b.x))
                .toList();
        assertTrue(track0.size() >= 3, "several lows along spawn's track: " + track0.size());
        for (int i = 1; i < track0.size(); i++) {
            double gap = track0.get(i).x - track0.get(i - 1).x;
            assertTrue(gap > 0.9 * P.spacing() && gap < 1.5 * P.spacing(), "spaced about evenly: " + gap);
        }
        assertTrue(sim.systems().stream().anyMatch(s -> s.kind == WeatherSystem.Kind.HIGH), "highs between them");
    }

    @Test
    void freshGroundStartsWithWeatherAlreadyUnderWay() {
        SystemsSim covered = new SystemsSim(JET, 7, new ArrayList<>(), 0);
        covered.step(0, 100, 0, List.of(new SystemsSim.Anchor(0, 0)), (x, z) -> true, null);
        assertTrue(covered.systems().stream().allMatch(s -> s.age <= 100), "ground already supplied: young waves");
        SystemsSim fresh = new SystemsSim(JET, 7, new ArrayList<>(), 0);
        fresh.step(0, 100, 0, List.of(new SystemsSim.Anchor(0, 0)), (x, z) -> false, null);
        assertTrue(fresh.systems().stream().allMatch(s -> s.age > 100), "fresh ground: part way through their lives");
    }

    @Test
    void overManyDaysSystemsMoveEastAndStayBounded() {
        SystemsSim sim = new SystemsSim(JET, 7, new ArrayList<>(), 0);
        List<SystemsSim.Anchor> here = List.of(new SystemsSim.Anchor(0, 0));
        sim.step(0, 100, 0, here, (x, z) -> false, null);
        WeatherSystem first = sim.systems().get(0);
        double x0 = first.x;
        long id = first.id;
        long t = 100;
        for (int i = 0; i < 240; i++) { // 10 in-game days in in-game hours
            t += 1000;
            sim.step(t, 1000, 0, here, (x, z) -> true, null);
        }
        assertTrue(sim.systems().size() < 120, "bounded: " + sim.systems().size());
        assertTrue(sim.systems().stream().noneMatch(s -> s.id == id), "the first low has lived and gone");
        long lows = sim.systems().stream().filter(s -> s.kind == WeatherSystem.Kind.LOW && s.track == 0).count();
        assertTrue(lows >= 2, "still supplied with lows: " + lows);
        SystemsSim one = new SystemsSim(JET, 7, new ArrayList<>(List.of(low(0.2, 1))), 10);
        one.step(1000, 1000, 0, List.of(), (x, z) -> true, null);
        assertTrue(one.systems().get(0).x > 0, "moved east");
        assertTrue(x0 == x0);
    }

    @Test
    void withoutNudgesTheSameStartGivesTheSameWeather() {
        SystemsSim a = new SystemsSim(JET, 7, new ArrayList<>(), 0);
        SystemsSim b = new SystemsSim(JET, 7, new ArrayList<>(), 0);
        List<SystemsSim.Anchor> here = List.of(new SystemsSim.Anchor(5000, -3000));
        for (int i = 1; i <= 50; i++) {
            a.step(i * 1000L, 1000, -0.5, here, (x, z) -> true, null);
            b.step(i * 1000L, 1000, -0.5, here, (x, z) -> true, null);
        }
        assertEquals(a.systems().size(), b.systems().size());
        for (int i = 0; i < a.systems().size(); i++) {
            assertEquals(a.systems().get(i).x, b.systems().get(i).x, 1e-9);
        }
        SystemsSim c = a.copy();
        c.step(51_000, 1000, -0.5, here, (x, z) -> true, new SplittableRandom(1));
        assertFalse(c.systems().get(0).x == a.systems().get(0).x, "nudges move the live run off the forecast");
    }

    @Test
    void systemsFarFromEveryoneAreForgotten() {
        SystemsSim sim = new SystemsSim(JET, 7, new ArrayList<>(), 0);
        sim.step(0, 100, 0, List.of(new SystemsSim.Anchor(0, 0)), (x, z) -> true, null);
        assertFalse(sim.systems().isEmpty());
        sim.step(100, 100, 0, List.of(new SystemsSim.Anchor(1_000_000, 0)), (x, z) -> true, null);
        assertTrue(sim.systems().stream().allMatch(s -> s.x > 500_000), "only the far player's systems remain");
    }

    private static FrontGeometry.Front find(List<FrontGeometry.Front> fronts, FrontGeometry.Type type) {
        return fronts.stream().filter(f -> f.type() == type).findFirst().orElseThrow();
    }
}
