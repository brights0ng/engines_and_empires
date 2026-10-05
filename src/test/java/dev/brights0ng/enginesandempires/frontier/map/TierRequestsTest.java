package dev.brights0ng.enginesandempires.frontier.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TierRequestsTest {

    private static final TierRequests.Asked LAST = new TierRequests.Asked(0, 0, 40, 1_000_000);

    @Test
    void aMapAsksTheFirstTimeAndEveryTenSeconds() {
        assertTrue(TierRequests.shouldAsk(null, 0, 0, 40, 5));
        assertFalse(TierRequests.shouldAsk(LAST, 0, 0, 40, 1_000_000 + 9_999));
        assertTrue(TierRequests.shouldAsk(LAST, 0, 0, 40, 1_000_000 + 10_000));
    }

    @Test
    void movingOrZoomingOutAsksAgainButNotTooOften() {
        assertTrue(TierRequests.shouldAsk(LAST, 11, 0, 40, 1_000_000 + 600), "moved more than a quarter of the way");
        assertFalse(TierRequests.shouldAsk(LAST, 5, 0, 40, 1_000_000 + 600), "a small move is still covered");
        assertTrue(TierRequests.shouldAsk(LAST, 0, 0, 80, 1_000_000 + 600), "zoomed out");
        assertFalse(TierRequests.shouldAsk(LAST, 0, 0, 80, 1_000_000 + 100), "but no more than twice a second");
    }
}
