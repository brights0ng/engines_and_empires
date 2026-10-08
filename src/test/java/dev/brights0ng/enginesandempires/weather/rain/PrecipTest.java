package dev.brights0ng.enginesandempires.weather.rain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.field.WarmNose;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;

/** Phase 5a: what falls, from the temperature on the way down; hail bursts. */
class PrecipTest {

    @Test
    void withoutAWarmLayerItIsSnowMixedOrRainByTheGround() {
        assertEquals(Precip.SNOW, Precip.decide(-5, 0, 0));
        assertEquals(Precip.SNOW, Precip.decide(0.5, 0, 0), "wet snow just above freezing");
        assertEquals(Precip.MIXED, Precip.decide(2, 0, 0));
        assertEquals(Precip.RAIN, Precip.decide(8, 0, 0));
    }

    @Test
    void aWarmLayerAloftMakesSleetAndFreezingRainOverFreezingGround() {
        assertEquals(Precip.FREEZING_RAIN, Precip.decide(-2, 2.5, 0.2), "fully melted, refreezes on contact");
        assertEquals(Precip.SLEET, Precip.decide(-2, 0.8, 0.5), "half melted, refreezes on the way down");
        assertEquals(Precip.SLEET, Precip.decide(-8, 2.5, 0.8), "fully melted but a deep, very cold layer below");
        assertEquals(Precip.RAIN, Precip.decide(4, 2.5, 0.2), "warm ground: rain");
        assertEquals(Precip.SNOW, Precip.decide(-2, 0.1, 0.9), "a trace of warmth: still snow");
    }

    @Test
    void mixedFallsAsRainInSomeStreaksAndSnowInOthers() {
        int snow = 0;
        for (int i = 0; i < 400; i++) {
            Precip p = Precip.MIXED.look(i * 7, i * 13);
            assertTrue(p == Precip.RAIN || p == Precip.SNOW);
            if (p == Precip.SNOW) {
                snow++;
            }
        }
        assertTrue(snow > 120 && snow < 280, "about half: " + snow);
        assertEquals(Precip.SLEET, Precip.SLEET.look(3, 4));
    }

    /**
     * Ahead of a warm front, in winter: freezing rain near the front, sleet further ahead, snow beyond; nothing behind
     * the low or in the warm sector itself.
     */
    @Test
    void theTextbookSequenceAheadOfAWarmFront() {
        WeatherSystem low = new WeatherSystem(1, WeatherSystem.Kind.LOW, 0, 0, 0, 1, 45_000, 100_000, 25, 4000, false);
        // Warm (+6 C) south of the warm front's line (15 degrees south of east), cold (-4 C) elsewhere.
        java.util.function.DoubleBinaryOperator surface = (x, z) -> z > Math.tan(Math.toRadians(15)) * x && x > 0
                ? 6 : -4;
        double[] near = WarmNose.at(List.of(low), 3000, 500, surface);
        double[] mid = WarmNose.at(List.of(low), 3000, -200, surface);
        double[] far = WarmNose.at(List.of(low), 3000, -1500, surface);
        double[] behind = WarmNose.at(List.of(low), -3000, -1000, surface);
        System.out.printf("melt near %.2f, mid %.2f, far %.2f, behind %.2f%n", near[0], mid[0], far[0], behind[0]);
        assertEquals(Precip.FREEZING_RAIN, Precip.decide(-3, near[0], near[1]));
        assertEquals(Precip.SLEET, Precip.decide(-3, mid[0], mid[1]));
        assertEquals(Precip.SNOW, Precip.decide(-3, far[0], far[1]));
        assertEquals(0, behind[0], 1e-9);
        assertEquals(0, WarmNose.at(List.of(low), 3000, 2500, surface)[0], 1e-9, "the warm sector itself");
    }

    @Test
    void hailFallsInBurstsFromStrongStormsOnly() {
        UUID id = new UUID(5, 7);
        RainModel.Cloud storm = cloud(id, true, 0.9);
        RainModel.Cloud weak = cloud(id, true, 0.2);
        RainModel.Cloud shower = cloud(id, false, 0.9);
        int stormTicks = 0, windows = 0;
        boolean prev = false;
        List<Integer> bursts = new ArrayList<>();
        for (int t = 0; t < 120_000; t += 20) {
            boolean h = RainModel.hailAt(storm, 0.9, t);
            if (h) {
                stormTicks += 20;
            }
            if (h && !prev) {
                bursts.add(t);
            }
            prev = h;
            assertFalse(RainModel.hailAt(weak, 0.9, t), "weak convection: no hail");
            assertFalse(RainModel.hailAt(shower, 0.9, t), "no thunder: no hail");
            assertFalse(RainModel.hailAt(storm, 0.4, t), "outside the heart of the shaft: no hail");
        }
        windows = 120_000 / (int) RainModel.HAIL_WINDOW;
        double share = stormTicks / 120_000.0;
        System.out.printf("strong storm: hail %.0f%% of the time, %d bursts in %d windows%n", share * 100,
                bursts.size(), windows);
        assertTrue(share > 0.08 && share < 0.45, "in bursts, not all the time: " + share);
        assertEquals(RainModel.hailAt(storm, 0.9, 12_345), RainModel.hailAt(storm, 0.9, 12_345), "deterministic");
    }

    private static RainModel.Cloud cloud(UUID id, boolean thunder, double convection) {
        return new RainModel.Cloud(id, "cumulonimbus_calvus", thunder, 300, 2000, 0, 0, 1, 0.9, 0, 0, 500, List.of(),
                List.of(), Double.NEGATIVE_INFINITY, convection);
    }
}
