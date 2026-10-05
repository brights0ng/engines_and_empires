package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReaderWindingTest {

    @Test
    void windingTakesTenSecondsAtTwentyTicksASecond() {
        assertEquals(200, ReaderWinding.WIND_TICKS);
        assertEquals(10.0, ReaderWinding.WIND_TICKS / 20.0, 0.0);
    }

    @Test
    void aFreshReaderIsNotWound() {
        assertFalse(ReaderWinding.isFullyWound(0.0F));
    }

    @Test
    void windingFromEmptyTakesExactlyWindTicksToFinish() {
        float charge = 0.0F;
        int ticks = 0;
        while (!ReaderWinding.isFullyWound(charge)) {
            charge = ReaderWinding.advance(charge);
            ticks++;
            assertTrue(ticks <= ReaderWinding.WIND_TICKS, "took more ticks than it should");
        }
        assertEquals(ReaderWinding.WIND_TICKS, ticks);
    }

    @Test
    void chargeNeverExceedsAFullWindAndSettlesExactlyAtOne() {
        float charge = 0.0F;
        for (int i = 0; i < ReaderWinding.WIND_TICKS + 50; i++) {
            charge = ReaderWinding.advance(charge);
            assertTrue(charge <= 1.0F, "charge went over 1.0 at tick " + i);
        }
        assertEquals(1.0F, charge, 0.0F, "it settles exactly at 1.0, not just close to it");
    }

    /** A partial wind stays partial, and resuming continues from where it left off rather than restarting, since advance() is a
     * pure function of the current charge, with no hidden state of its own. */
    @Test
    void oneTickShortOfAFullWindIsNotWoundAndResumingFinishesIt() {
        float charge = 0.0F;
        for (int i = 0; i < ReaderWinding.WIND_TICKS - 1; i++) {
            charge = ReaderWinding.advance(charge);
        }
        assertFalse(ReaderWinding.isFullyWound(charge), "one tick short of a full wind must not count as wound");
        charge = ReaderWinding.advance(charge);
        assertTrue(ReaderWinding.isFullyWound(charge), "the next tick finishes it");
    }

    @Test
    void theTotalExhaustionMatchesTwoAndAHalfFoodBars() {
        // Each of the ten icons on the hunger bar is 2 hunger points; 2.5 icons is 5 points. Vanilla takes 4.0 exhaustion for
        // each hunger point it removes, so five points costs 20.0 exhaustion in all.
        assertEquals(5.0F, ReaderWinding.FOOD_POINTS_SPENT, 0.0F);
        assertEquals(20.0F, ReaderWinding.TOTAL_EXHAUSTION, 0.0F);
    }

    @Test
    void theExhaustionAddsUpToTheTotalOverAFullWind() {
        float total = ReaderWinding.EXHAUSTION_PER_TICK * ReaderWinding.WIND_TICKS;
        assertEquals(ReaderWinding.TOTAL_EXHAUSTION, total, 1.0e-4F);
    }
}
