// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.TaskUtility.Candidate;

class FarmerUtilityTest {

    @Test
    void dumpUrgencyOvertakesHarvestWhenPocketsFullAndFallsBackWhenIdle() {
        double harvestFull = TaskUtility.score(true, FarmerUtility.BASE_HARVEST_WEIGHT,
                FarmerUtility.harvestUrgency(200), true, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS);
        double dumpFull = TaskUtility.score(true, FarmerUtility.BASE_DUMP_WEIGHT,
                FarmerUtility.dumpUrgency(1.0, true), false, TaskUtility.DEFAULT_MOMENTUM_BONUS);
        assertTrue(dumpFull > harvestFull,
                "full pockets dump (" + dumpFull + ") must beat active harvest with momentum (" + harvestFull + ")");

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
    void harvestAndSeedChooseLargerBacklogWhenFreshAndCommitWithMomentum() {
        assertEquals(FarmerUtility.BASE_HARVEST_WEIGHT, FarmerUtility.BASE_PLANT_WEIGHT);
        assertTrue(FarmerUtility.fieldWorkUrgency(101) > FarmerUtility.fieldWorkUrgency(100),
                "field work urgency stays strictly monotonic even above 100 plots");

        // Fresh choice: more seedable farmland -> seed wins
        String pickMoreEmpty = TaskUtility.pickBest(List.of(
                new Candidate<>("harvest", true, FarmerUtility.BASE_HARVEST_WEIGHT,
                        FarmerUtility.harvestUrgency(10), false, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS),
                new Candidate<>("seed", true, FarmerUtility.BASE_PLANT_WEIGHT,
                        FarmerUtility.plantUrgency(11), false, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS)));
        assertEquals("seed", pickMoreEmpty);

        // Fresh choice: more ripe crops -> harvest wins
        String pickMoreRipe = TaskUtility.pickBest(List.of(
                new Candidate<>("harvest", true, FarmerUtility.BASE_HARVEST_WEIGHT,
                        FarmerUtility.harvestUrgency(12), false, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS),
                new Candidate<>("seed", true, FarmerUtility.BASE_PLANT_WEIGHT,
                        FarmerUtility.plantUrgency(11), false, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS)));
        assertEquals("harvest", pickMoreRipe);

        // Fresh tie -> harvest wins (declared first)
        String pickTie = TaskUtility.pickBest(List.of(
                new Candidate<>("harvest", true, FarmerUtility.BASE_HARVEST_WEIGHT,
                        FarmerUtility.harvestUrgency(15), false, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS),
                new Candidate<>("seed", true, FarmerUtility.BASE_PLANT_WEIGHT,
                        FarmerUtility.plantUrgency(15), false, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS)));
        assertEquals("harvest", pickTie);

        // Active harvest momentum: even 1 ripe crop left beats 200 empty farmland plots
        String keepHarvesting = TaskUtility.pickBest(List.of(
                new Candidate<>("harvest", true, FarmerUtility.BASE_HARVEST_WEIGHT,
                        FarmerUtility.harvestUrgency(1), true, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS),
                new Candidate<>("seed", true, FarmerUtility.BASE_PLANT_WEIGHT,
                        FarmerUtility.plantUrgency(200), false, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS)));
        assertEquals("harvest", keepHarvesting);

        // Active seed momentum: even 1 empty plot left beats 200 ripe crops
        String keepSeeding = TaskUtility.pickBest(List.of(
                new Candidate<>("harvest", true, FarmerUtility.BASE_HARVEST_WEIGHT,
                        FarmerUtility.harvestUrgency(200), false, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS),
                new Candidate<>("seed", true, FarmerUtility.BASE_PLANT_WEIGHT,
                        FarmerUtility.plantUrgency(1), true, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS)));
        assertEquals("seed", keepSeeding);
    }

    @Test
    void interruptionPrioritiesOrderDumpAboveRetillAndFetchAboveActiveFieldWork() {
        double maxActiveField = TaskUtility.score(true, FarmerUtility.BASE_FIELD_WORK_WEIGHT,
                FarmerUtility.fieldWorkUrgency(10_000), true, FarmerUtility.FIELD_WORK_MOMENTUM_BONUS);
        double fetchSeeds = TaskUtility.score(true, FarmerUtility.BASE_FETCH_WEIGHT,
                FarmerUtility.fetchUrgency(false, 1), false, TaskUtility.DEFAULT_MOMENTUM_BONUS);
        double retill = TaskUtility.score(true, FarmerUtility.BASE_RETILL_WEIGHT,
                FarmerUtility.retillUrgency(1), false, TaskUtility.DEFAULT_MOMENTUM_BONUS);
        double fetchHoe = TaskUtility.score(true, FarmerUtility.BASE_FETCH_WEIGHT,
                FarmerUtility.fetchUrgency(true, 0), false, TaskUtility.DEFAULT_MOMENTUM_BONUS);
        double fullDump = TaskUtility.score(true, FarmerUtility.BASE_DUMP_WEIGHT,
                FarmerUtility.dumpUrgency(1.0, true), false, TaskUtility.DEFAULT_MOMENTUM_BONUS);

        assertTrue(fetchSeeds > maxActiveField, "seed fetch (" + fetchSeeds + ") beats active field (" + maxActiveField + ")");
        assertTrue(retill > fetchSeeds, "retill (" + retill + ") beats seed fetch (" + fetchSeeds + ")");
        assertTrue(fetchHoe > maxActiveField, "hoe fetch (" + fetchHoe + ") beats active field (" + maxActiveField + ")");
        assertTrue(fullDump > retill && fullDump > fetchHoe,
                "full dump (" + fullDump + ") beats retill (" + retill + ") and hoe fetch (" + fetchHoe + ")");
    }

    @Test
    void scaledWorkTicksScalesWithHeavyLaborAndProductivity() {
        assertEquals(12, FarmerUtility.scaledWorkTicks(12, 1.0, 1.0));
        assertEquals(24, FarmerUtility.scaledWorkTicks(12, 1.0, 0.5));
        assertTrue(FarmerUtility.scaledWorkTicks(16, 1.4, 1.0) < 16);
        assertTrue(FarmerUtility.scaledWorkTicks(16, 0.7, 1.0) > 16);
        assertEquals(1, FarmerUtility.scaledWorkTicks(1, 5.0, 1.0));
        // Skill speed scaling: 0.5x at 0.0 -> 24t; 1.0x at 0.25 -> 12t; 2.5x at 1.0 -> 5t.
        assertEquals(24, FarmerUtility.scaledWorkTicks(12, 1.0, 1.0, CivilianSkills.speedMultiplier(0.0)));
        assertEquals(12, FarmerUtility.scaledWorkTicks(12, 1.0, 1.0, CivilianSkills.speedMultiplier(0.25)));
        assertEquals(5, FarmerUtility.scaledWorkTicks(12, 1.0, 1.0, CivilianSkills.speedMultiplier(1.0)));
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

    @Test
    void harvestThresholdRequiresAtLeastFivePercentOfPlantedCrops() {
        assertEquals(300, FarmerUtility.IDLE_WORK_RECHECK_TICKS);
        assertFalse(FarmerUtility.meetsHarvestThreshold(0, 0));
        assertFalse(FarmerUtility.meetsHarvestThreshold(0, 20));
        assertFalse(FarmerUtility.meetsHarvestThreshold(1, 0));
        assertTrue(FarmerUtility.meetsHarvestThreshold(1, 3));
        assertTrue(FarmerUtility.meetsHarvestThreshold(1, 20), "1/20 = 5.0% meets threshold");
        assertFalse(FarmerUtility.meetsHarvestThreshold(1, 21), "1/21 = 4.76% is below 5%");
        assertTrue(FarmerUtility.meetsHarvestThreshold(2, 21), "2/21 = 9.52% meets threshold");
        assertFalse(FarmerUtility.meetsHarvestThreshold(4, 100), "4/100 = 4% is below 5%");
        assertTrue(FarmerUtility.meetsHarvestThreshold(5, 100), "5/100 = 5% meets threshold");
    }
}
