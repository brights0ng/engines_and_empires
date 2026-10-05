package dev.brights0ng.enginesandempires.frontier.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.frontier.Tier;

class TierRulesTest {

    private static final TierParams P = TierParams.DEFAULTS;
    private static final int SETTLE = P.settleTicks();

    @Test
    void theChosenNumbers() {
        assertEquals(36_000, P.settleTicks(), "1.5 days to settle");
        assertEquals(168_000, P.graceTicks(), "7 days' grace");
        assertEquals(4, P.nearRadius());
        assertEquals(4, P.nearMultiplier());
        assertEquals(8, P.ringRadius());
        assertEquals(4, P.areaRadius());
        assertEquals(5, P.civilizedGuards());
        assertEquals(0, P.frontierBelowY());
        assertEquals(54, P.uninhabitedBelowY());
    }

    @Test
    void settledNeedsAnAnchorAndBeingLivedIn() {
        assertEquals(Tier.SETTLED, TierRules.base(new AreaCounts(1, SETTLE, false, 0), P));
        assertEquals(Tier.FRONTIER, TierRules.base(new AreaCounts(0, SETTLE, false, 0), P), "inhabited but no anchor");
        assertEquals(Tier.FRONTIER, TierRules.base(new AreaCounts(3, SETTLE - 1, false, 0), P), "anchors but not lived in long enough");
        assertEquals(Tier.FRONTIER, TierRules.base(AreaCounts.EMPTY, P));
    }

    @Test
    void aRecentResidentCountsAsLivedIn() {
        assertEquals(Tier.SETTLED, TierRules.base(new AreaCounts(1, 0, true, 0), P), "a village found for the first time");
        assertEquals(Tier.FRONTIER, TierRules.base(new AreaCounts(0, 0, true, 0), P), "a pillager patrol in open country");
    }

    @Test
    void civilizedIsSettledAndGuarded() {
        assertEquals(Tier.CIVILIZED, TierRules.base(new AreaCounts(1, SETTLE, false, 5), P));
        assertEquals(Tier.SETTLED, TierRules.base(new AreaCounts(1, SETTLE, false, 4), P));
        assertEquals(Tier.FRONTIER, TierRules.base(new AreaCounts(0, 0, false, 9), P), "guards in the wild are not a town");
    }

    @Test
    void landNearSettledLandIsUninhabited() {
        assertEquals(Tier.UNINHABITED, TierRules.resolve(true, Tier.FRONTIER, true, 70, P));
        assertEquals(Tier.FRONTIER, TierRules.resolve(true, Tier.FRONTIER, false, 70, P));
        assertEquals(Tier.SETTLED, TierRules.resolve(true, Tier.SETTLED, true, 70, P));
    }

    @Test
    void belowZeroIsAlwaysFrontier() {
        assertEquals(Tier.FRONTIER, TierRules.resolve(true, Tier.CIVILIZED, true, -1, P));
        assertEquals(Tier.UNINHABITED, TierRules.resolve(true, Tier.CIVILIZED, true, 0, P), "y 0 itself is above the line");
    }

    @Test
    void belowFiftyFourIsAtBestUninhabited() {
        assertEquals(Tier.UNINHABITED, TierRules.resolve(true, Tier.SETTLED, false, 53, P));
        assertEquals(Tier.SETTLED, TierRules.resolve(true, Tier.SETTLED, false, 54, P));
        assertEquals(Tier.UNINHABITED, TierRules.resolve(true, Tier.FRONTIER, true, 30, P));
        assertEquals(Tier.FRONTIER, TierRules.resolve(true, Tier.FRONTIER, false, 30, P), "the cap never raises a tier");
    }

    @Test
    void otherDimensionsAreUninhabitedEverywhere() {
        assertEquals(Tier.UNINHABITED, TierRules.resolve(false, Tier.FRONTIER, false, -30, P));
        assertEquals(Tier.UNINHABITED, TierRules.resolve(false, Tier.CIVILIZED, false, 100, P));
    }

    @Test
    void onlySettledSectionsReachingFiftyFourTameTheLandAround() {
        assertTrue(TierRules.isSettledSource(Tier.SETTLED, 63, P), "section 48-63 reaches y 54");
        assertTrue(TierRules.isSettledSource(Tier.CIVILIZED, 79, P));
        assertFalse(TierRules.isSettledSource(Tier.SETTLED, 47, P), "a bed in a mine");
        assertFalse(TierRules.isSettledSource(Tier.FRONTIER, 79, P));
    }
}
