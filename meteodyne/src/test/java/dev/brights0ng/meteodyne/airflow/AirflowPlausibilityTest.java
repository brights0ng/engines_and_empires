// Written by Claude (2026-11-21): the plausibility band.
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AirflowPlausibilityTest {

    @Test
    void theBandHoldsWhatTheGameMakesAndNothingThatRanAway() {
        for (double t : new double[] {-100, -40, 0, 25, 840, 1000, 1500}) {
            assertTrue(AirflowPlausibility.plausible(t), t + " C");
        }
        for (double t : new double[] {-100.01, 1500.01, 1e4, -1e6, 3.4e38, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(AirflowPlausibility.plausible(t), t + " C");
        }
        assertTrue(AirflowPlausibility.plausibleOffset(1600));
        assertTrue(AirflowPlausibility.plausibleOffset(-1600));
        assertFalse(AirflowPlausibility.plausibleOffset(1601));
        assertFalse(AirflowPlausibility.plausibleOffset(Float.MAX_VALUE));
        assertFalse(AirflowPlausibility.plausibleOffset(Double.NaN));
    }
}
