// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GodSatietyTest {

    private static final long YEAR = 1_728_000L;

    @Test
    void thresholds() {
        assertEquals(1_296_000L, GodSatiety.warnTicks(YEAR));
        assertEquals(GodSatiety.State.SATED, GodSatiety.state(0, YEAR));
        assertEquals(GodSatiety.State.SATED, GodSatiety.state(1_295_999, YEAR));
        assertEquals(GodSatiety.State.HUNGRY, GodSatiety.state(1_296_000, YEAR));
        assertEquals(GodSatiety.State.HUNGRY, GodSatiety.state(YEAR - 1, YEAR));
        assertEquals(GodSatiety.State.ABANDONED, GodSatiety.state(YEAR, YEAR));
        assertEquals(GodSatiety.State.ABANDONED, GodSatiety.state(YEAR * 2, YEAR));
        // 75% of 10 is 7.5: hungry from 8.
        assertEquals(GodSatiety.State.SATED, GodSatiety.state(7, 10));
        assertEquals(GodSatiety.State.HUNGRY, GodSatiety.state(8, 10));
    }

    @Test
    void theWarningIsCrossedExactlyOnce() {
        int crossings = 0;
        for (long hunger = 0; hunger < YEAR; hunger += 20) {
            if (GodSatiety.crossedWarning(hunger, hunger + 20, YEAR)) {
                crossings++;
                assertTrue(hunger + 20 >= GodSatiety.warnTicks(YEAR) && hunger < GodSatiety.warnTicks(YEAR));
            }
        }
        assertEquals(1, crossings);
        assertFalse(GodSatiety.crossedWarning(1_296_000, 1_296_020, YEAR), "already hungry");
        assertTrue(GodSatiety.crossedWarning(0, YEAR, YEAR), "a jump past the warning crosses it");
    }

    @Test
    void aHungryGodPutsTheFireOutEveryIntervalOfHunger() {
        long warn = GodSatiety.warnTicks(YEAR);
        assertTrue(GodSatiety.extinguishDue(warn - 20, warn, YEAR), "on becoming hungry");
        int due = 0;
        for (long hunger = warn; hunger < warn + 3000; hunger += 20) {
            if (GodSatiety.extinguishDue(hunger, hunger + 20, YEAR)) {
                due++;
            }
        }
        assertEquals(3000 / GodSatiety.EXTINGUISH_INTERVAL_TICKS, due);
        assertFalse(GodSatiety.extinguishDue(0, 300, YEAR), "a sated god leaves the fire alone");
        assertFalse(GodSatiety.extinguishDue(YEAR - 20, YEAR, YEAR), "an abandoning god leaves the fire alone");
    }

    @Test
    void aLimitOfZeroOrLessNeverGrowsHungry() {
        for (long limit : new long[]{0, -1, Long.MIN_VALUE}) {
            assertEquals(GodSatiety.State.SATED, GodSatiety.state(0, limit));
            assertEquals(GodSatiety.State.SATED, GodSatiety.state(Long.MAX_VALUE, limit));
            assertFalse(GodSatiety.crossedWarning(0, Long.MAX_VALUE, limit));
            assertFalse(GodSatiety.extinguishDue(0, Long.MAX_VALUE, limit));
        }
    }
}
