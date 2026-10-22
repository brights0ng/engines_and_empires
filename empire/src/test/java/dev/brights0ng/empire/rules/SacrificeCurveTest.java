// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SacrificeCurveTest {

    private static final double EPS = 0.005;

    @Test
    void nothingAtOrBelowEachTiersStart() {
        assertEquals(0, SacrificeCurve.q(1, 0));
        assertEquals(0, SacrificeCurve.q(1, -3));
        assertEquals(0, SacrificeCurve.q(2, 5));
        assertEquals(0, SacrificeCurve.q(3, 17));
        assertEquals(0, SacrificeCurve.q(4, 28));
        assertEquals(0, SacrificeCurve.q(5, 38));
    }

    @Test
    void eachTierPeaksAtItsQmax() {
        assertEquals(24, SacrificeCurve.q(1, 12), EPS);
        assertEquals(128, SacrificeCurve.q(2, 20), EPS);
        assertEquals(90, SacrificeCurve.q(3, 30), EPS);
        assertEquals(16, SacrificeCurve.q(4, 45), EPS);
        assertEquals(48, SacrificeCurve.q(5, 60), EPS);
    }

    @Test
    void tiersOneToThreeTaperToNothingAtTheirEnd() {
        assertEquals(0, SacrificeCurve.q(1, 27));
        assertEquals(0, SacrificeCurve.q(1, 40));
        assertEquals(0, SacrificeCurve.q(2, 37));
        assertEquals(0, SacrificeCurve.q(3, 50));
        assertEquals(0, SacrificeCurve.q(3, 1000));
        // Halfway down the taper is half the peak
        assertEquals(12, SacrificeCurve.q(1, 19.5), EPS);
        assertEquals(64, SacrificeCurve.q(2, 28.5), EPS);
    }

    @Test
    void tiersFourAndFiveKeepGrowingPastThePeak() {
        assertTrue(SacrificeCurve.q(4, 100) > SacrificeCurve.q(4, 80));
        assertTrue(SacrificeCurve.q(5, 200) > SacrificeCurve.q(5, 100));
        assertTrue(SacrificeCurve.q(4, 1_000_000) > 0);
    }

    @Test
    void brightsReferenceValues() {
        assertEquals(42.67, SacrificeCurve.q(2, 10), EPS);
        assertEquals(90, SacrificeCurve.q(3, 30), EPS);
        assertEquals(28.0, SacrificeCurve.q(4, 80), 0.05);
        assertEquals(48, SacrificeCurve.q(5, 60), EPS);
    }

    @Test
    void rampIsLinear() {
        assertEquals(10, SacrificeCurve.q(1, 5), EPS);
        assertEquals(45, SacrificeCurve.q(3, 23.5), EPS);
    }

    @Test
    void unknownTiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SacrificeCurve.q(0, 10));
        assertThrows(IllegalArgumentException.class, () -> SacrificeCurve.q(6, 10));
    }
}
