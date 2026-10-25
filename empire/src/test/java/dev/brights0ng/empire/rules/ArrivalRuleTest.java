// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArrivalRuleTest {

    private static final long NOW = 50_000;
    private static final long NEXT = 48_000;

    @Test
    void allGatesOpenLetsATravellerArrive() {
        assertTrue(ArrivalRule.canArrive(true, true, 0, false, NOW, NEXT));
        assertTrue(ArrivalRule.canArrive(true, true, ArrivalRule.PIONEER_LIMIT - 1, false, NOW, NOW));
    }

    @Test
    void anUnlitBonfireBlocks() {
        assertFalse(ArrivalRule.canArrive(false, true, 0, false, NOW, NEXT));
    }

    @Test
    void noMemberNearBlocks() {
        assertFalse(ArrivalRule.canArrive(true, false, 0, false, NOW, NEXT));
    }

    @Test
    void threeCitizensBlock() {
        assertFalse(ArrivalRule.canArrive(true, true, ArrivalRule.PIONEER_LIMIT, false, NOW, NEXT));
        assertFalse(ArrivalRule.canArrive(true, true, 3, false, NOW, NEXT));
    }

    @Test
    void aWaitingTravellerBlocks() {
        assertFalse(ArrivalRule.canArrive(true, true, 0, true, NOW, NEXT));
    }

    @Test
    void beforeTheNextArrivalTimeBlocks() {
        assertFalse(ArrivalRule.canArrive(true, true, 0, false, NEXT - 1, NEXT));
    }

    @Test
    void theFirstTravellerCanComeStraightAfterFounding() {
        assertTrue(ArrivalRule.canArrive(true, true, 0, false, 0, 0));
    }
}
