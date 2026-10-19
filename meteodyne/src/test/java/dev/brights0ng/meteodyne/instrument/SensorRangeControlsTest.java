// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SensorRangeControlsTest {
    @Test
    void aHandleCanCrossTheOtherOneToInvertTheSignal() {
        var controls = new SensorRangeControls(0, 300);
        assertTrue(controls.move(true, 400, false));
        assertEquals(400, controls.zero());
        assertEquals(300, controls.full());
        assertFalse(controls.move(true, 300, false), "coincident endpoints must never be sent to the server");
        assertEquals(400, controls.zero());
    }

    @Test
    void controlDragMovesBothEndpointsAndRetainsTheSignedSpan() {
        for (boolean zero : new boolean[]{true, false}) {
            var controls = new SensorRangeControls(300, 0);
            int previous = controls.endpoint(zero);
            assertTrue(controls.move(zero, previous + 50, true));
            assertEquals(350, controls.zero());
            assertEquals(50, controls.full());
            assertEquals(-300, controls.full() - controls.zero());
        }
    }

    @Test
    void controlDragClampsTheWholeRangeAtEitherLimit() {
        var controls = new SensorRangeControls(0, 300);
        assertTrue(controls.move(true, 2000, true));
        assertEquals(1700, controls.zero());
        assertEquals(2000, controls.full());
        assertFalse(controls.move(false, 2000, true));
        assertTrue(controls.move(false, -1000, true));
        assertEquals(-1000, controls.zero());
        assertEquals(-700, controls.full());
    }

    @Test
    void draggingOutsideTheScaleClampsOnlyTheSelectedEndpoint() {
        var controls = new SensorRangeControls(0, 300);
        controls.move(true, Integer.MIN_VALUE, false);
        controls.move(false, Integer.MAX_VALUE, false);
        assertEquals(SensorRangeControls.MIN, controls.zero());
        assertEquals(SensorRangeControls.MAX, controls.full());
    }

    @Test
    void everyTenthRoundTripsThroughTheScaleIncludingNegativeAndExtremeValues() {
        for (int value = SensorRangeControls.MIN; value <= SensorRangeControls.MAX; value++) {
            assertEquals(value, SensorRangeControls.atFraction(SensorRangeControls.fraction(value)));
        }
        assertEquals(SensorRangeControls.MIN, SensorRangeControls.atFraction(-1));
        assertEquals(SensorRangeControls.MAX, SensorRangeControls.atFraction(2));
    }

    @Test
    void keyboardAndScrollCanMakeFineAdjustmentsWithoutMovingTheOtherHandle() {
        var controls = new SensorRangeControls(0, 300);
        controls.move(true, controls.zero() + 1, false);
        assertEquals(1, controls.zero());
        assertEquals(300, controls.full());
        controls.move(false, controls.full() - 10, false);
        assertEquals(290, controls.full());
        assertEquals(1, controls.zero());
    }

    @Test
    void badInitialRangesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SensorRangeControls(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SensorRangeControls(-1001, 100));
        assertThrows(IllegalArgumentException.class, () -> new SensorRangeControls(100, 2001));
    }
}
