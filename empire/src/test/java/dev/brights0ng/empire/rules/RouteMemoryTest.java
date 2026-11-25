// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RouteMemoryTest {

    @Test
    void unseenRouteUsesBaselineAndLearnedRouteSmoothsWithEma() {
        RouteMemory mem = new RouteMemory();
        long workPos = BlockKeys.pack(0, 64, 0);
        long pubPos = BlockKeys.pack(35, 64, 0);
        int base = RouteMemory.baselineTicks(workPos, pubPos, CivilianDerivedStats.BASE_MOVEMENT_SPEED);
        assertEquals(base, mem.estimateTicks(LocationKey.WORK, LocationKey.FOOD_SITE, workPos, pubPos,
                CivilianDerivedStats.BASE_MOVEMENT_SPEED));

        // First real walk takes 15s (300t)
        mem.recordTrip(LocationKey.WORK, LocationKey.FOOD_SITE, 300, base);
        assertEquals(300, mem.learnedTicks(LocationKey.WORK, LocationKey.FOOD_SITE));
        // Symmetric lookup
        assertEquals(300, mem.learnedTicks(LocationKey.FOOD_SITE, LocationKey.WORK));

        // Creeper hole slows walk to 30s (600t) -> EMA raises estimate immediately
        mem.recordTrip(LocationKey.WORK, LocationKey.FOOD_SITE, 600, base);
        assertEquals(450, mem.learnedTicks(LocationKey.WORK, LocationKey.FOOD_SITE));

        // Hole repaired -> 15s walk pulls estimate back down
        mem.recordTrip(LocationKey.WORK, LocationKey.FOOD_SITE, 300, base);
        assertEquals(375, mem.learnedTicks(LocationKey.WORK, LocationKey.FOOD_SITE));

        // Outlier snag (e.g. 5 minutes stuck) is clamped to prevent poisoning
        mem.recordTrip(LocationKey.WORK, LocationKey.FOOD_SITE, 10_000, base);
        int maxCap = Math.max(RouteMemory.MIN_OUTLIER_CAP_TICKS, base * RouteMemory.MAX_OUTLIER_MULTIPLIER);
        assertTrue(mem.learnedTicks(LocationKey.WORK, LocationKey.FOOD_SITE) <= maxCap);
    }

    @Test
    void movingAnchorInvalidatesTouchingRoutesOnly() {
        RouteMemory mem = new RouteMemory();
        long home1 = BlockKeys.pack(0, 64, 0);
        long work = BlockKeys.pack(40, 64, 0);
        long pub = BlockKeys.pack(0, 64, 40);
        mem.updateAnchor(LocationKey.HOME, home1);
        mem.updateAnchor(LocationKey.WORK, work);
        mem.updateAnchor(LocationKey.FOOD_SITE, pub);
        mem.recordTrip(LocationKey.HOME, LocationKey.WORK, 300, 300);
        mem.recordTrip(LocationKey.WORK, LocationKey.FOOD_SITE, 400, 400);

        // Relocating HOME by 20 blocks invalidates HOME-WORK but keeps WORK-FOOD_SITE
        mem.updateAnchor(LocationKey.HOME, BlockKeys.pack(20, 64, 0));
        assertEquals(-1, mem.learnedTicks(LocationKey.HOME, LocationKey.WORK));
        assertEquals(400, mem.learnedTicks(LocationKey.WORK, LocationKey.FOOD_SITE));

        // Save/restore round-trip
        RouteMemory restored = RouteMemory.restore(mem.save());
        assertEquals(mem.save(), restored.save());
    }

    @Test
    void crowdMemoryExpiresAfterThreeDaysAndSmoothsMealPeaks() {
        CrowdMemory cm = new CrowdMemory();
        assertFalse(cm.likelyOccupied(LocationKey.FOOD_SITE, 10L));
        cm.recordSighting(LocationKey.FOOD_SITE, 10L, ScheduleClock.mmss(9, 20));
        assertTrue(cm.likelyOccupied(LocationKey.FOOD_SITE, 10L));
        assertTrue(cm.likelyOccupied(LocationKey.FOOD_SITE, 13L));
        assertFalse(cm.likelyOccupied(LocationKey.FOOD_SITE, 14L));
        assertEquals(ScheduleClock.mmss(9, 20), cm.observedLunchTick());

        // Second sighting smooths peak
        cm.recordSighting(LocationKey.FOOD_SITE, 11L, ScheduleClock.mmss(10, 0));
        assertTrue(cm.observedLunchTick() > ScheduleClock.mmss(9, 20)
                && cm.observedLunchTick() < ScheduleClock.mmss(10, 0));

        assertEquals(cm.save(), CrowdMemory.restore(cm.save()).save());
    }
}
