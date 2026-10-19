// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SensorRangeTest {
    @Test
    void endpointsAndMidpointHaveTheExpectedSignal() {
        SensorRange range = new SensorRange(0, 30);
        assertEquals(0, range.signal(-20));
        assertEquals(0, range.signal(0));
        assertEquals(8, range.signal(15));
        assertEquals(15, range.signal(30));
        assertEquals(15, range.signal(100));
    }

    @Test
    void reversedRangeMakesColdGiveTheStrongSignal() {
        SensorRange range = new SensorRange(30, 0);
        assertEquals(15, range.signal(-20));
        assertEquals(15, range.signal(0));
        assertEquals(8, range.signal(15));
        assertEquals(0, range.signal(30));
        assertEquals(0, range.signal(100));
    }

    @Test
    void unknownAndInfiniteReadingsNeverPowerAnything() {
        for (SensorRange range : new SensorRange[]{new SensorRange(0, 30), new SensorRange(30, 0)}) {
            for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertEquals(0, range.signal(invalid));
            }
        }
    }

    @Test
    void invalidEndpointsAreRejectedAndTenthsRoundoffIsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> new SensorRange(5, 5));
        assertThrows(IllegalArgumentException.class, () -> new SensorRange(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new SensorRange(Double.NaN, 5));
        assertThrows(IllegalArgumentException.class, () -> new SensorRange(5, Double.POSITIVE_INFINITY));
        assertEquals(15, new SensorRange(12.1, 12.2).signal(12.2));
        assertEquals(15, new SensorRange(-12.1, -12.2).signal(-12.2));
    }

    @Test
    void allSignalsRemainBoundedAndMonotoneAcrossAWholeScale() {
        SensorRange rising = new SensorRange(-30, 50), falling = new SensorRange(50, -30);
        int lastRising = 0, lastFalling = 15;
        for (int t = -1000; t <= 2000; t++) {
            int a = rising.signal(t / 10.0), b = falling.signal(t / 10.0);
            assertTrue(a >= lastRising && a <= 15);
            assertTrue(b <= lastFalling && b >= 0);
            lastRising = a;
            lastFalling = b;
        }
    }
}
