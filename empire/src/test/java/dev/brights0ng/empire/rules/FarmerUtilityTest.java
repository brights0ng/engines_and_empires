// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FarmerUtilityTest {

    @Test
    void dumpUrgencyOvertakesHarvestWhenPocketsFullAndFallsBackWhenIdle() {
        double harvestFull = TaskUtility.score(true, FarmerUtility.BASE_HARVEST_WEIGHT,
                FarmerUtility.harvestUrgency(20), true, TaskUtility.DEFAULT_MOMENTUM_BONUS);
        double dumpFull = TaskUtility.score(true, FarmerUtility.BASE_DUMP_WEIGHT,
                FarmerUtility.dumpUrgency(1.0, true), false, TaskUtility.DEFAULT_MOMENTUM_BONUS);
        assertTrue(dumpFull > harvestFull,
                "full pockets dump (" + dumpFull + ") must beat active harvest (" + harvestFull + ")");

        double dumpHalfBusy = TaskUtility.score(true, FarmerUtility.BASE_DUMP_WEIGHT,
                FarmerUtility.dumpUrgency(0.5, true), false, TaskUtility.DEFAULT_MOMENTUM_BONUS);
        assertTrue(dumpHalfBusy < harvestFull,
                "half-full pockets (" + dumpHalfBusy + ") should not interrupt active harvesting (" + harvestFull + ")");

        double dumpIdlePartial = TaskUtility.score(true, FarmerUtility.BASE_DUMP_WEIGHT,
                FarmerUtility.dumpUrgency(0.2, false), false, TaskUtility.DEFAULT_MOMENTUM_BONUS);
        double waitScore = TaskUtility.score(true, FarmerUtility.BASE_WAIT_WEIGHT, 1.0, false,
                TaskUtility.DEFAULT_MOMENTUM_BONUS);
        assertTrue(dumpIdlePartial > waitScore,
                "partial harvest dump when idle (" + dumpIdlePartial + ") must beat waiting at shed (" + waitScore + ")");
    }

    @Test
    void scaledWorkTicksScalesWithHeavyLaborAndProductivity() {
        assertEquals(12, FarmerUtility.scaledWorkTicks(12, 1.0, 1.0));
        assertEquals(24, FarmerUtility.scaledWorkTicks(12, 1.0, 0.5));
        assertTrue(FarmerUtility.scaledWorkTicks(16, 1.4, 1.0) < 16);
        assertTrue(FarmerUtility.scaledWorkTicks(16, 0.7, 1.0) > 16);
        assertEquals(1, FarmerUtility.scaledWorkTicks(1, 5.0, 1.0));
    }

    @Test
    void desiredSeedKeepCountCapsAtOneStack() {
        assertEquals(0, FarmerUtility.desiredSeedKeepCount(0, 64));
        assertEquals(0, FarmerUtility.desiredSeedKeepCount(-5, 64));
        assertEquals(18, FarmerUtility.desiredSeedKeepCount(18, 64));
        assertEquals(64, FarmerUtility.desiredSeedKeepCount(120, 64));
        assertEquals(16, FarmerUtility.desiredSeedKeepCount(20, 16));
    }

    @Test
    void majorityKeyPicksHighestCountWithDeterministicTieBreak() {
        assertNull(FarmerUtility.majorityKey(Map.of()));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("minecraft:wheat_seeds", 5);
        counts.put("minecraft:potato", 40);
        assertEquals("minecraft:potato", FarmerUtility.majorityKey(counts));

        Map<String, Integer> tied = new LinkedHashMap<>();
        tied.put("minecraft:wheat_seeds", 10);
        tied.put("minecraft:carrot", 10);
        assertEquals("minecraft:carrot", FarmerUtility.majorityKey(tied));
    }
}
