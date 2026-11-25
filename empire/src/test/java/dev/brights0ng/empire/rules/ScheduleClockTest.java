// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScheduleClockTest {

    @Test
    void midnightAnchorMapsVanillaTimesCorrectly() {
        assertEquals(0, ScheduleClock.timeOfDay(18_000L));
        assertEquals("00:00", ScheduleClock.format(ScheduleClock.timeOfDay(18_000L)));
        assertEquals(ScheduleClock.SUNRISE_TICK, ScheduleClock.timeOfDay(24_000L));
        assertEquals("05:00", ScheduleClock.format(ScheduleClock.timeOfDay(24_000L)));
        assertEquals(ScheduleClock.NOON_TICK, ScheduleClock.timeOfDay(30_000L));
        assertEquals("10:00", ScheduleClock.format(ScheduleClock.timeOfDay(30_000L)));
        assertEquals(ScheduleClock.SUNSET_TICK, ScheduleClock.timeOfDay(36_000L));
        assertEquals("15:00", ScheduleClock.format(ScheduleClock.timeOfDay(36_000L)));

        // Day rolls over at vanilla 18,000 (midnight)
        assertEquals(0L, ScheduleClock.scheduleDay(18_000L));
        assertEquals(-1L, ScheduleClock.scheduleDay(17_999L));
        assertEquals(1L, ScheduleClock.scheduleDay(42_000L));
    }

    @Test
    void tonightBedtimeMatchesCivilianSleepExactly() {
        for (int need : new int[] {5_400, 6_000, 7_200, 8_400, 9_600, 10_200}) {
            long vanillaOwnBedtime = CivilianSleep.ownBedtime(10L, need);
            int expectedOnScheduleClock = ScheduleClock.timeOfDay(vanillaOwnBedtime);
            assertEquals(expectedOnScheduleClock, ScheduleClock.tonightBedtimeTick(need), "need=" + need);
        }
    }

    @Test
    void outdoorWindowIsFourToSixteen() {
        assertFalse(ScheduleClock.isOutdoorTime(ScheduleClock.mmss(3, 59)));
        assertTrue(ScheduleClock.isOutdoorTime(ScheduleClock.mmss(4, 0)));
        assertTrue(ScheduleClock.isOutdoorTime(ScheduleClock.mmss(12, 0)));
        assertTrue(ScheduleClock.isOutdoorTime(ScheduleClock.mmss(15, 59)));
        assertFalse(ScheduleClock.isOutdoorTime(ScheduleClock.mmss(16, 0)));
        assertFalse(ScheduleClock.isOutdoorTime(ScheduleClock.mmss(0, 0)));
    }

    @Test
    void continueSleepCapsAtNightEnd() {
        assertEquals(ScheduleClock.mmss(4, 0), ScheduleClock.continueSleepEndTick(ScheduleClock.mmss(4, 0)));
        assertEquals(ScheduleClock.NIGHT_END_TICK, ScheduleClock.continueSleepEndTick(ScheduleClock.mmss(6, 0)));
        assertEquals(0, ScheduleClock.continueSleepEndTick(-100));
    }
}
