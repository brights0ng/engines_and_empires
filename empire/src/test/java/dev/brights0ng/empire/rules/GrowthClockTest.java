// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrowthClockTest {

    private static final long CHUNK = ChunkKeys.pack(3, -4);
    private static final long SCAN = 20;

    @Test
    void theFirstSightingPaysNothing() {
        GrowthClock clock = new GrowthClock();
        assertEquals(GrowthClock.NEVER, clock.last(CHUNK));
        assertEquals(0, clock.markGrowing(CHUNK, 100_000, SCAN));
        assertEquals(100_000, clock.last(CHUNK));
    }

    @Test
    void growingAtEveryScanPaysNothing() {
        GrowthClock clock = new GrowthClock();
        clock.markGrowing(CHUNK, 1000, SCAN);
        assertEquals(0, clock.markGrowing(CHUNK, 1020, SCAN));
        assertEquals(0, clock.markGrowing(CHUNK, 1040, SCAN));
    }

    @Test
    void aGapPaysTheTimeBetweenSightingsLessOneScan() {
        GrowthClock clock = new GrowthClock();
        clock.markGrowing(CHUNK, 1000, SCAN);
        assertEquals(5000 - 1000 - SCAN, clock.markGrowing(CHUNK, 5000, SCAN));
        assertEquals(5000, clock.last(CHUNK));
        assertEquals(0, clock.markGrowing(CHUNK, 5020, SCAN), "paid once, not again");
    }

    @Test
    void timeGoingBackwardsPaysNothing() {
        GrowthClock clock = new GrowthClock();
        clock.set(CHUNK, 9000);
        assertEquals(0, clock.markGrowing(CHUNK, 8000, SCAN));
    }

    @Test
    void aForgottenChunkStartsAfresh() {
        GrowthClock clock = new GrowthClock();
        clock.markGrowing(CHUNK, 1000, SCAN);
        clock.forget(CHUNK);
        assertEquals(GrowthClock.NEVER, clock.last(CHUNK));
        assertEquals(0, clock.markGrowing(CHUNK, 90_000, SCAN), "no back pay after forgetting");
    }

    @Test
    void restorePutsBackOnlyAnUntouchedTime() {
        GrowthClock clock = new GrowthClock();
        clock.set(CHUNK, 1000);
        clock.markGrowing(CHUNK, 5000, SCAN);
        clock.restore(CHUNK, 5000, 1000);
        assertEquals(1000, clock.last(CHUNK));

        clock.set(CHUNK, 6000);
        clock.restore(CHUNK, 5000, 1000);
        assertEquals(6000, clock.last(CHUNK), "a newer time is kept");

        long other = ChunkKeys.pack(9, 9);
        clock.markGrowing(other, 7000, SCAN);
        clock.restore(other, 7000, GrowthClock.NEVER);
        assertEquals(GrowthClock.NEVER, clock.last(other), "restoring 'never' removes the entry");
        assertEquals(1, clock.size());
    }
}
