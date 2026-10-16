package dev.brights0ng.enginesandempires.weather.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApparentTest {

    @Test
    void windChillMatchesTheStandardFormula() {
        // At 0 C: 5, 10 and 17 m/s feel like about -5, -7 and -9 C (the planning doc's checked numbers)
        assertEquals(4.9, Apparent.windChillDrop(0, 5), 0.1);
        assertEquals(7.05, Apparent.windChillDrop(0, 10), 0.1);
        assertEquals(8.8, Apparent.windChillDrop(0, 17), 0.1);
        // -10 C, 17 m/s: about -23 C, a 13 C drop
        assertEquals(12.7, Apparent.windChillDrop(-10, 17), 0.2);
    }

    @Test
    void windChillEasesInAtBothEdges() {
        assertEquals(0, Apparent.windChillDrop(10, 15), 1e-12, "mild air: none");
        assertEquals(0, Apparent.windChillDrop(20, 15), 1e-12);
        assertEquals(0, Apparent.windChillDrop(-5, 1.3), 1e-12, "a breath of wind: none");
        assertEquals(0, Apparent.windChillDrop(-5, 0), 1e-12);
        double a = Apparent.windChillDrop(8, 10);
        double b = Apparent.windChillDrop(6, 10);
        double c = Apparent.windChillDrop(4, 10);
        assertTrue(0 < a && a < b && b < c, "more at lower temperatures: " + a + " " + b + " " + c);
        double w1 = Apparent.windChillDrop(-5, 1.8);
        double w2 = Apparent.windChillDrop(-5, 2.4);
        double w3 = Apparent.windChillDrop(-5, 4);
        assertTrue(0 < w1 && w1 < w2 && w2 < w3, "more in more wind: " + w1 + " " + w2 + " " + w3);
        // No step anywhere across the temperature edge
        double prev = Apparent.windChillDrop(10, 8);
        for (double t = 10; t >= 4; t -= 0.05) {
            double d = Apparent.windChillDrop(t, 8);
            assertTrue(Math.abs(d - prev) < 0.12, "smooth at " + t + ": " + prev + " -> " + d);
            prev = d;
        }
    }

    @Test
    void heatIndexMatchesTheNws() {
        assertEquals(8.4, Apparent.heatIndexRise(32, 0.70), 0.2, "32 C at 70% feels like 40 C");
        assertEquals(18, Apparent.heatIndexRise(36, 0.70), 1.5, "36 C at 70% about +18");
        assertTrue(Apparent.heatIndexRise(32, 0.30) < 1.0, "dry heat adds almost nothing");
        assertTrue(Apparent.heatIndexRise(32, 0.90) > Apparent.heatIndexRise(32, 0.70), "more with more humidity");
    }

    @Test
    void heatIndexEasesInAndNeverCools() {
        assertEquals(0, Apparent.heatIndexRise(24, 0.9), 1e-12);
        assertEquals(0, Apparent.heatIndexRise(10, 0.9), 1e-12);
        assertEquals(0, Apparent.heatIndexRise(-20, 1), 1e-12);
        double prev = 0;
        for (double t = 24; t <= 40; t += 0.1) {
            double d = Apparent.heatIndexRise(t, 0.6);
            assertTrue(d >= 0, "never cools at " + t);
            assertTrue(Math.abs(d - prev) < 0.9, "smooth at " + t + ": " + prev + " -> " + d);
            prev = d;
        }
    }

    @Test
    void relativeWindIsTheWindMinusOnesOwnMotion() {
        assertEquals(5, Apparent.relativeWind(5, 0, 0, 0), 1e-12);
        assertEquals(0, Apparent.relativeWind(5, 0, 5, 0), 1e-12, "moving with the wind: calm");
        assertEquals(15, Apparent.relativeWind(5, 0, -10, 0), 1e-12, "heading into it");
        assertEquals(5, Apparent.relativeWind(0, 0, 3, 4), 1e-12, "a ship in still air");
        assertEquals(5, Apparent.relativeWind(3, 0, 0, 4), 1e-9, "crosswind");
    }

    @Test
    void scaleAndCapApplyToEachOffset() {
        Apparent.Settings twice = new Apparent.Settings(2, false, 0);
        assertEquals(10, twice.apply(5), 1e-12);
        Apparent.Settings off = new Apparent.Settings(0, false, 0);
        assertEquals(0, off.apply(5), 1e-12, "scale 0 turns it off");
        Apparent.Settings capped = new Apparent.Settings(1, true, 6);
        assertEquals(6, capped.apply(13.4), 1e-12);
        assertEquals(4, capped.apply(4), 1e-12, "under the cap");
        Apparent.Settings both = new Apparent.Settings(2, true, 6);
        assertEquals(6, both.apply(5), 1e-12, "scaled, then capped");
        assertEquals(13.4, Apparent.Settings.DEFAULT.apply(13.4), 1e-12, "default: scale 1, no cap");
        assertEquals(0, Apparent.Settings.DEFAULT.apply(-3), 1e-12);
    }
}
