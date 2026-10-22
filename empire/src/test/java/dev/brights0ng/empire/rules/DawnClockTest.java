// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DawnClockTest {

    @Test
    void dayCalculationsMatchDayBoundaries() {
        assertEquals(0, DawnClock.dayOf(0));
        assertEquals(0, DawnClock.dayOf(1000));
        assertEquals(0, DawnClock.dayOf(23999));
        assertEquals(1, DawnClock.dayOf(24000));
        assertEquals(1, DawnClock.dayOf(47999));
        assertEquals(2, DawnClock.dayOf(48000));
        assertEquals(-1, DawnClock.dayOf(-1));
    }

    @Test
    void nextDawnIsNextDay() {
        assertEquals(1, DawnClock.nextDawnDay(0));
        assertEquals(1, DawnClock.nextDawnDay(12000));
        assertEquals(1, DawnClock.nextDawnDay(23999));
        assertEquals(2, DawnClock.nextDawnDay(24000));
    }
}
