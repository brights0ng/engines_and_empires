package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Refresh targets, rebuild order and remembered empty sections. */
class RebuildScheduleTest {

    @Test
    void nearCloudsAimForTwoSecondsAndFarOnesForThirty() {
        assertEquals(2, RebuildSchedule.distanceSeconds(0), 1e-9);
        assertEquals(2, RebuildSchedule.distanceSeconds(512), 1e-9);
        assertEquals(30, RebuildSchedule.distanceSeconds(2048), 1e-9);
        assertEquals(30, RebuildSchedule.distanceSeconds(6000), 1e-9);
        double mid = RebuildSchedule.distanceSeconds(1024);
        assertTrue(mid > 2 && mid < 30, "in between rises smoothly: " + mid);
        assertTrue(RebuildSchedule.distanceSeconds(1500) > mid);
    }

    @Test
    void bornOrDyingCloudsAreRefreshedFastWhereverTheyAre() {
        assertEquals(2, RebuildSchedule.targetSeconds(5000, true, 0.01, 0.5, 1), 1e-9);
        assertEquals(30, RebuildSchedule.targetSeconds(5000, false, 0.01, 0.5, 1), 1e-9);
    }

    @Test
    void aStormTooBigForItsTargetGetsALongerOne() {
        // 4 s of build time on half a core, at most half of it: once every 16 s.
        assertEquals(16, RebuildSchedule.targetSeconds(0, false, 4, 0.5, 1), 1e-9);
        assertEquals(2, RebuildSchedule.targetSeconds(0, false, 0, 0.5, 1), 1e-9, "unknown cost: the near target");
        assertEquals(3, RebuildSchedule.targetSeconds(0, false, 0, 0.5, 3), 1e-9, "the configured gap is a floor");
    }

    @Test
    void aGenerationStartsEarlyEnoughToBeShownOnTime() {
        // Drawn meshes from tick 100, 40-tick target, the last generation took 10 ticks: start at 130.
        assertEquals(130, RebuildSchedule.dueTick(100, 100, 40, 10, 20));
        // ...but never sooner than the gap after the last start.
        assertEquals(120, RebuildSchedule.dueTick(100, 100, 40, 35, 20));
    }

    @Test
    void aDueCheapCloudGoesAheadOfAStormThatNeedsSecondsMore() {
        double cumulus = RebuildSchedule.score(3, 2, 0.01);
        double storm = RebuildSchedule.score(20, 16, 4);
        assertTrue(cumulus > storm);
    }

    @Test
    void aStormWaitingLongEnoughComesFirstInTheEnd() {
        double cumulus = RebuildSchedule.score(2, 2, 0.01);
        double storm = RebuildSchedule.score(16 * 500, 16, 4);
        assertTrue(storm > cumulus);
        assertTrue(RebuildSchedule.score(20, 16, 0.5) > RebuildSchedule.score(20, 16, 4),
                "the less it has left, the sooner");
    }

    @Test
    void distanceToAFormationsBoxIsZeroInside() {
        double[] box = {-100, 100, 200, 400, -50, 50};
        assertEquals(0, RebuildSchedule.boxDistance(box, 0, 300, 0), 1e-9);
        assertEquals(100, RebuildSchedule.boxDistance(box, 0, 100, 0), 1e-9);
        assertEquals(50, RebuildSchedule.boxDistance(box, 130, 300, 90), 1e-9);
    }

    @Test
    void anEmptySectionIsLeftOutThenRechecked() {
        long key = RebuildSchedule.key(3, 0, -2);
        RebuildSchedule.Empty e = RebuildSchedule.emptied(key, 8, 10, 1000, false);
        assertTrue(e.recheckGen() >= 10 + RebuildSchedule.EMPTY_FAR_GENS);
        assertTrue(e.recheckGen() < 10 + RebuildSchedule.EMPTY_FAR_GENS + RebuildSchedule.EMPTY_FAR_SPREAD);
        assertTrue(RebuildSchedule.skip(e, 8, 11, 1040, false));
        assertFalse(RebuildSchedule.skip(e, 8, e.recheckGen(), 1100, false), "rechecked when its turn comes");
        assertFalse(RebuildSchedule.skip(e, 4, 11, 1040, false), "a finer voxel size is built again");
        assertFalse(RebuildSchedule.skip(e, 8, 11, 1000 + RebuildSchedule.EMPTY_MAX_TICKS, false),
                "never left unchecked past the limit");
        assertFalse(RebuildSchedule.skip(null, 8, 11, 1040, false));
    }

    @Test
    void aCloudBeingBornHasItsEmptySectionsRecheckedSoon() {
        RebuildSchedule.Empty e = RebuildSchedule.emptied(RebuildSchedule.key(1, 1, 1), 8, 10, 1000, false);
        assertTrue(RebuildSchedule.skip(e, 8, 11, 1040, true));
        assertFalse(RebuildSchedule.skip(e, 8, 10 + RebuildSchedule.EMPTY_NEAR_GENS, 1080, true),
                "back after two generations, not four to eight");
    }

    @Test
    void anEmptySectionTouchingCloudIsRecheckedSooner() {
        long key = RebuildSchedule.key(0, 1, 0);
        RebuildSchedule.Empty e = RebuildSchedule.touching(RebuildSchedule.emptied(key, 8, 10, 1000, false), true, 10);
        assertTrue(e.nearMesh());
        assertEquals(10 + RebuildSchedule.EMPTY_NEAR_GENS, e.recheckGen());
        // Cloud appears next to one left out long before: it comes back next generation.
        RebuildSchedule.Empty old = RebuildSchedule.emptied(key, 8, 10, 1000, false);
        assertEquals(14, RebuildSchedule.touching(old, true, 13).recheckGen());
    }

    @Test
    void sectionKeysRoundTripWithNegatives() {
        int[][] cases = {{0, 0, 0}, {-1, 5, -7}, {1000, -1000, 3}, {-1048576, 1048575, -1}};
        for (int[] c : cases) {
            long k = RebuildSchedule.key(c[0], c[1], c[2]);
            assertEquals(c[0], RebuildSchedule.keyX(k));
            assertEquals(c[1], RebuildSchedule.keyY(k));
            assertEquals(c[2], RebuildSchedule.keyZ(k));
        }
    }
}
