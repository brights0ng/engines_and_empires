// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CivilianSleepTest {

    @Test
    void neededTicksMatchesEnduranceTable() {
        int[] expected = {
                9600, 9257, 8914, 8571, 8229, 7886, 7543, 7200,
                7050, 6900, 6750, 6600, 6450, 6300, 6150, 6000
        };
        for (int raw = 3; raw <= 18; raw++) {
            assertEquals(expected[raw - 3], CivilianSleep.neededTicks(raw), "Endurance " + raw);
        }
        assertEquals(9600, CivilianSleep.neededTicks(0));
        assertEquals(6000, CivilianSleep.neededTicks(25));
    }

    @Test
    void nightBoundariesAndIndices() {
        assertFalse(CivilianSleep.isNight(0));
        assertFalse(CivilianSleep.isNight(12_541));
        assertTrue(CivilianSleep.isNight(12_542));
        assertTrue(CivilianSleep.isNight(18_001));
        assertTrue(CivilianSleep.isNight(23_459));
        assertFalse(CivilianSleep.isNight(23_460));
        assertTrue(CivilianSleep.isNight(24_000 + 12_542));

        assertEquals(-1L, CivilianSleep.nightIndex(0));
        assertEquals(-1L, CivilianSleep.nightIndex(12_541));
        assertEquals(0L, CivilianSleep.nightIndex(12_542));
        assertEquals(0L, CivilianSleep.nightIndex(23_459));
        assertEquals(0L, CivilianSleep.nightIndex(23_460));
        assertEquals(1L, CivilianSleep.nightIndex(24_000 + 12_542));
    }

    @Test
    void ownSleepPeriodIsCentredOnNightMidpoint() {
        assertEquals(13_201, CivilianSleep.bedtime(9600));
        assertEquals(22_801, CivilianSleep.wakeTime(9600));
        assertEquals(14_401, CivilianSleep.bedtime(7200));
        assertEquals(21_601, CivilianSleep.wakeTime(7200));
        assertEquals(15_001, CivilianSleep.bedtime(6000));
        assertEquals(21_001, CivilianSleep.wakeTime(6000));
    }

    @Test
    void initialStateIsNaNAndNoSession() {
        CivilianSleep sleep = new CivilianSleep();
        assertTrue(Float.isNaN(sleep.lastNight()));
        assertFalse(sleep.hasOpenSession());
        assertEquals(CivilianSleep.NO_DAY_TIME, sleep.lastDayTime());
    }

    @Test
    void fullBedSleepFinalisesAtOneAndPreventsSecondSessionSameNight() {
        CivilianSleep sleep = new CivilianSleep();
        int need = 7200;
        long start = CivilianSleep.ownBedtime(0, need);
        for (int i = 0; i < need - 1; i++) {
            sleep.tick(start + i, true, true, need);
            assertTrue(sleep.hasOpenSession());
            assertTrue(Float.isNaN(sleep.lastNight()));
        }
        sleep.tick(start + need - 1, true, true, need);
        assertFalse(sleep.hasOpenSession());
        assertTrue(sleep.isRestedFor(0));
        assertEquals(1.0F, sleep.lastNight(), 1e-6F);
        assertFalse(sleep.wantsToSleep(start + need, need));

        // Remaining ticks of the night and morning do not overwrite lastNight.
        sleep.tick(start + need, false, false, need);
        sleep.tick(CivilianSleep.NIGHT_END, false, false, need);
        assertEquals(1.0F, sleep.lastNight(), 1e-6F);
    }

    @Test
    void roughSleepCapsAtHalfAndCannotOversleepToMakeUpForIt() {
        CivilianSleep sleep = new CivilianSleep();
        int need = 6000;
        long start = CivilianSleep.ownBedtime(0, need);
        for (int i = 0; i < need; i++) {
            sleep.tick(start + i, true, false, need);
        }
        assertTrue(sleep.isRestedFor(0));
        assertEquals(0.5F, sleep.lastNight(), 1e-6F);

        // Lying down further during the same night adds no extra credit.
        for (int i = 0; i < 1000; i++) {
            sleep.tick(start + need + i, true, false, need);
        }
        assertEquals(0.5F, sleep.lastNight(), 1e-6F);
    }

    @Test
    void mixedBedAndRoughSleepCombinesCreditLinearly() {
        CivilianSleep sleep = new CivilianSleep();
        int need = 6000;
        long start = CivilianSleep.ownBedtime(0, need);
        for (int i = 0; i < 3000; i++) {
            sleep.tick(start + i, true, true, need);
        }
        for (int i = 3000; i < 6000; i++) {
            sleep.tick(start + i, true, false, need);
        }
        assertTrue(sleep.isRestedFor(0));
        assertEquals(0.75F, sleep.lastNight(), 1e-6F);
    }

    @Test
    void loadedCivilianThatNeverSleptFinalisesAtZero() {
        CivilianSleep sleep = new CivilianSleep();
        int need = 7200;
        sleep.tick(CivilianSleep.NIGHT_START, false, false, need);
        assertTrue(sleep.hasOpenSession());
        sleep.tick(CivilianSleep.NIGHT_END - 1, false, false, need);
        assertTrue(Float.isNaN(sleep.lastNight()));
        sleep.tick(CivilianSleep.NIGHT_END, false, false, need);
        assertFalse(sleep.hasOpenSession());
        assertEquals(0.0F, sleep.lastNight(), 1e-6F);
    }

    @Test
    void lateSleeperWakesAtEndOfNightWithPartialCredit() {
        CivilianSleep sleep = new CivilianSleep();
        int need = 7200;
        long lateStart = CivilianSleep.NIGHT_END - 1800;
        for (long t = lateStart; t < CivilianSleep.NIGHT_END; t++) {
            sleep.tick(t, true, true, need);
        }
        assertTrue(sleep.hasOpenSession());
        sleep.tick(CivilianSleep.NIGHT_END, false, false, need);
        assertFalse(sleep.hasOpenSession());
        assertEquals(0.25F, sleep.lastNight(), 1e-6F);
    }

    @Test
    void catchUpNightSkipBeforeBedtimeCreditsFullNight() {
        int need = 7200;
        CivilianSleep bed = new CivilianSleep();
        bed.tick(13_000L, false, false, need);
        bed.catchUp(13_001L, 24_000L, true, need);
        assertEquals(1.0F, bed.lastNight(), 1e-6F);
        assertTrue(bed.isRestedFor(0));
        assertFalse(bed.hasOpenSession());

        CivilianSleep rough = new CivilianSleep();
        rough.tick(13_000L, false, false, need);
        rough.catchUp(13_001L, 24_000L, false, need);
        assertEquals(0.5F, rough.lastNight(), 1e-6F);
        assertTrue(rough.isRestedFor(0));
    }

    @Test
    void catchUpNightSkipMidSleepPeriodCreditsRemainingSlice() {
        CivilianSleep sleep = new CivilianSleep();
        int need = 7200;
        sleep.tick(18_000L, false, false, need);
        // Missed 14401..18000 while awake; skipped 18001..24000 covers 18001..21601 = 3600 ticks.
        sleep.catchUp(18_001L, 24_000L, true, need);
        assertEquals(0.5F, sleep.lastNight(), 1e-6F);
        assertFalse(sleep.hasOpenSession());
    }

    @Test
    void catchUpWithinNightKeepsSessionOpenWithoutFinalising() {
        CivilianSleep sleep = new CivilianSleep();
        int need = 7200;
        sleep.tick(14_400L, false, false, need);
        sleep.catchUp(14_401L, 18_001L, true, need);
        assertTrue(sleep.hasOpenSession());
        assertEquals(3600, sleep.sleptTicks());
        assertEquals(3600.0F, sleep.creditTicks(), 1e-6F);
        assertTrue(Float.isNaN(sleep.lastNight()));

        // Finishing the remaining 3600 ticks live completes the night cleanly.
        for (int i = 0; i < 3600; i++) {
            sleep.tick(18_001L + i, true, true, need);
        }
        assertEquals(1.0F, sleep.lastNight(), 1e-6F);
        assertTrue(sleep.isRestedFor(0));
    }

    @Test
    void catchUpAlreadySleepingLiveCombinesWithSkippedTailWithoutDoubleCounting() {
        CivilianSleep sleep = new CivilianSleep();
        int need = 7200;
        for (int i = 0; i < 1000; i++) {
            sleep.tick(14_401L + i, true, true, need);
        }
        assertEquals(1000, sleep.sleptTicks());
        // lastDayTime is 15400; catchUp starts at 15401.
        sleep.catchUp(15_401L, 24_000L, true, need);
        assertEquals(1.0F, sleep.lastNight(), 1e-6F);
        assertTrue(sleep.isRestedFor(0));
    }

    @Test
    void catchUpAcrossManyUnloadedNightsIsExactAndO1() {
        int need = 7200;
        CivilianSleep bed = new CivilianSleep();
        bed.catchUp(1_000L, 24_000L * 100_000L + 6_000L, true, need);
        assertEquals(1.0F, bed.lastNight(), 1e-6F);
        assertFalse(bed.hasOpenSession());

        CivilianSleep rough = new CivilianSleep();
        // Ends halfway through night 50000's sleep period.
        long endMidNight = 50_000L * 24_000L + 18_001L;
        rough.catchUp(1_000L, endMidNight, false, need);
        assertEquals(0.5F, rough.lastNight(), 1e-6F);
        assertTrue(rough.hasOpenSession());
        assertEquals(50_000L, rough.sessionNight());
        assertEquals(3600, rough.sleptTicks());
        assertEquals(1800.0F, rough.creditTicks(), 1e-6F);
    }

    @Test
    void catchUpDaytimeOnlyDoesNotAlterLastNight() {
        CivilianSleep sleep = new CivilianSleep();
        sleep.catchUp(1_000L, 12_542L, true, 7200);
        assertTrue(Float.isNaN(sleep.lastNight()));
        assertFalse(sleep.hasOpenSession());
        assertEquals(12_542L, sleep.lastDayTime());
    }

    @Test
    void catchUpBackwardJumpDoesNotCreditSleep() {
        CivilianSleep sleep = new CivilianSleep();
        sleep.tick(16_000L, true, true, 7200);
        assertEquals(1, sleep.sleptTicks());
        sleep.catchUp(16_000L, 6_000L, true, 7200);
        assertTrue(Float.isNaN(sleep.lastNight()));
        assertFalse(sleep.hasOpenSession());
        assertEquals(6_000L, sleep.lastDayTime());
    }

    @Test
    void customRoughCreditRateAppliesLiveAndInCatchUp() {
        int need = 6000;
        CivilianSleep live = new CivilianSleep();
        long start = CivilianSleep.ownBedtime(0, need);
        for (int i = 0; i < need; i++) {
            live.tick(start + i, true, false, need, 0.60F);
        }
        assertTrue(live.isRestedFor(0));
        assertEquals(0.60F, live.lastNight(), 1e-5F);

        CivilianSleep caught = new CivilianSleep();
        caught.catchUp(1_000L, 24_000L + 6_000L, false, need, 0.40F);
        assertEquals(0.40F, caught.lastNight(), 1e-5F);
    }

    @Test
    void savedFormAndCopyRoundTripIndependently() {
        CivilianSleep sleep = new CivilianSleep();
        sleep.tick(14_401L, true, true, 7200);
        sleep.setLastNight(0.8F);
        CivilianSleep.Saved saved = sleep.save();

        CivilianSleep restored = CivilianSleep.restore(saved);
        assertEquals(saved, restored.save());

        CivilianSleep copy = sleep.copy();
        assertNotSame(sleep, copy);
        assertEquals(saved, copy.save());
        copy.tick(14_402L, true, true, 7200);
        assertEquals(1, sleep.sleptTicks());
        assertEquals(2, copy.sleptTicks());
    }
}
