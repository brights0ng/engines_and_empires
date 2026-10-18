package dev.brights0ng.meteodyne.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.meteodyne.rain.Precip;

class RainSoakTest {

    private static final double SPEED = 0.0125;
    private static final double MAX = 0.2;

    @Test
    void rulingsFromBright() {
        assertEquals(RainSoak.FULL, RainSoak.factor(Precip.RAIN, 0.6));
        assertEquals(RainSoak.FULL, RainSoak.factor(Precip.FREEZING_RAIN, 0.2), "freezing rain soaks at the normal speed at any strength");
        assertEquals(RainSoak.FULL, RainSoak.factor(Precip.MIXED, 0.2));
        assertEquals(0.25, RainSoak.factor(Precip.RAIN, 0.15), "drizzle is a quarter");
        assertEquals(0.25, RainSoak.factor(Precip.SLEET, 0.9), "sleet is a quarter at any strength");
    }

    @Test
    void drizzleBecomesRainAtTheThreshold() {
        assertEquals(RainSoak.DRIZZLE, RainSoak.factor(Precip.RAIN, RainSoak.DRIZZLE_BELOW - 0.01));
        assertEquals(RainSoak.FULL, RainSoak.factor(Precip.RAIN, RainSoak.DRIZZLE_BELOW));
    }

    @Test
    void snowAndHailAreOurCall() {
        assertEquals(0.125, RainSoak.factor(Precip.SNOW, 0.8));
        assertEquals(0.5, RainSoak.factor(Precip.HAIL, 0.8));
        assertTrue(RainSoak.factor(Precip.SNOW, 1) < RainSoak.factor(Precip.SLEET, 1), "snow soaks slower than sleet");
    }

    @Test
    void nothingFallingSoaksNothing() {
        assertEquals(0, RainSoak.factor(null, 0.9));
    }

    @Test
    void rainDeepensWetnessBySpeedTimesFactor() {
        assertEquals(-SPEED, RainSoak.soak(0, 1, SPEED, MAX, 1), 1e-12);
        assertEquals(-SPEED / 4, RainSoak.soak(0, 0.25, SPEED, MAX, 1), 1e-12);
        assertEquals(-0.05 - SPEED, RainSoak.soak(-0.05, 1, SPEED, MAX, 1), 1e-12);
        assertEquals(-SPEED * 3, RainSoak.soak(0, 1, SPEED, MAX, 3), 1e-12, "three updates at once");
    }

    @Test
    void drizzleTakesFourTimesAsLongToGetAsWetAsRain() {
        double rain = 0;
        int rainUpdates = 0;
        while (rain > -0.1 + 1e-9) {
            rain = RainSoak.soak(rain, 1, SPEED, MAX, 1);
            rainUpdates++;
        }
        double drizzle = 0;
        int drizzleUpdates = 0;
        while (drizzle > -0.1 + 1e-9) {
            drizzle = RainSoak.soak(drizzle, 0.25, SPEED, MAX, 1);
            drizzleUpdates++;
        }
        assertEquals(4.0, (double) drizzleUpdates / rainUpdates, 1e-9);
    }

    @Test
    void rainStopsAtTheCap() {
        double w = 0;
        for (int i = 0; i < 1000; i++) {
            w = RainSoak.soak(w, 1, SPEED, MAX, 1);
        }
        assertEquals(-MAX, w, 1e-12);
        assertEquals(-MAX, RainSoak.soak(-MAX + 0.001, 1, SPEED, MAX, 1), 1e-12, "the last step lands on the cap, not past it");
    }

    @Test
    void rainNeverDriesSomeoneWetterFromWater() {
        assertEquals(-0.9, RainSoak.soak(-0.9, 1, SPEED, MAX, 1), "wetter than the cap: left as they are");
    }

    @Test
    void zeroesChangeNothing() {
        assertEquals(-0.1, RainSoak.soak(-0.1, 0, SPEED, MAX, 1));
        assertEquals(-0.1, RainSoak.soak(-0.1, 1, 0, MAX, 1));
        assertEquals(-0.1, RainSoak.soak(-0.1, 1, SPEED, MAX, 0));
    }
}
