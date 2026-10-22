// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClaimRequestsTest {

    @Test
    void shouldAskIfNeverAsked() {
        assertTrue(ClaimRequests.shouldAsk(null, 10, 10, 16, 1000));
    }

    @Test
    void shouldNotAskIfWithinMinGap() {
        ClaimRequests.Asked last = new ClaimRequests.Asked(10, 10, 16, 1000);
        assertFalse(ClaimRequests.shouldAsk(last, 10, 10, 16, 1200)); // 200ms < 500ms
    }

    @Test
    void shouldAskIfRefreshIntervalPassed() {
        ClaimRequests.Asked last = new ClaimRequests.Asked(10, 10, 16, 1000);
        assertTrue(ClaimRequests.shouldAsk(last, 10, 10, 16, 1000 + ClaimRequests.REFRESH_MILLIS + 1));
    }

    @Test
    void shouldAskIfZoomedOut() {
        ClaimRequests.Asked last = new ClaimRequests.Asked(10, 10, 16, 1000);
        assertTrue(ClaimRequests.shouldAsk(last, 10, 10, 32, 1600)); // radius 32 > 16
    }

    @Test
    void shouldAskIfMovedPastSlack() {
        ClaimRequests.Asked last = new ClaimRequests.Asked(10, 10, 16, 1000);
        // Slack is max(1, 16/4) = 4
        assertFalse(ClaimRequests.shouldAsk(last, 12, 10, 16, 1600)); // delta 2 <= 4
        assertTrue(ClaimRequests.shouldAsk(last, 15, 10, 16, 1600)); // delta 5 > 4
    }
}
