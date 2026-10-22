// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class DemandRollerTest {

    private static final long MSB = 0x1234_5678_9ABC_DEF0L;
    private static final long LSB = 0x0FED_CBA9_8765_4321L;

    private static DemandOption item(String id, double m) {
        return new DemandOption(DemandLine.Kind.ITEM, List.of(id), m);
    }

    /** One distinct option per tier, so a line's id says which tier it came from. */
    private static final List<List<DemandOption>> ONE_PER_TIER = List.of(
            List.of(item("t:one", 1.0)),
            List.of(item("t:two", 0.5)),
            List.of(item("t:three", 0.1)),
            List.of(item("t:four", 0.5)),
            List.of(item("t:five", 0.5)));

    /** The Iron Aurochs' table. */
    private static final List<List<DemandOption>> AUROCHS = List.of(
            List.of(item("minecraft:beef", 1.0)),
            List.of(item("minecraft:wheat", 0.5), item("minecraft:leather", 0.17)),
            List.of(item("createbigcannons:cast_iron_ingot", 0.09), item("farm_and_charm:goulash", 0.07)),
            List.of(item("minecraft:blaze_rod", 1.0), item("minecraft:breeze_rod", 0.5)),
            List.of(item("createbigcannons:nethersteel_ingot", 0.12),
                    item("farm_and_charm:beef_patty_with_vegetables", 0.5)));

    private static Set<String> ids(List<DemandLine> lines) {
        Set<String> ids = new HashSet<>();
        for (DemandLine line : lines) {
            ids.addAll(line.ids());
        }
        return ids;
    }

    @Test
    void sameInputsRollTheSameDemand() {
        for (int count = 0; count < 20; count++) {
            assertEquals(DemandRoller.roll(MSB, LSB, count, 30, AUROCHS), DemandRoller.roll(MSB, LSB, count, 30, AUROCHS));
        }
    }

    @Test
    void theRollChangesWithTheSacrificeCountAndTheEmpire() {
        Set<List<DemandLine>> seen = new HashSet<>();
        for (int count = 0; count < 40; count++) {
            seen.add(DemandRoller.roll(MSB, LSB, count, 30, AUROCHS));
        }
        assertTrue(seen.size() > 1, "40 sacrifices in a row should not all roll the same demand");

        Set<List<DemandLine>> empires = new HashSet<>();
        for (long lsb = 0; lsb < 40; lsb++) {
            empires.add(DemandRoller.roll(MSB, lsb, 0, 30, AUROCHS));
        }
        assertTrue(empires.size() > 1, "40 empires should not all roll the same first demand");
    }

    @Test
    void anOptionNeedsHalfAUnitToBeEligible() {
        // m = 0.025: q 16 gives 0.4 (not eligible), q 20 gives 0.5 (eligible)
        assertFalse(DemandRoller.eligible(16, 0.025));
        assertTrue(DemandRoller.eligible(20, 0.025));
    }

    @Test
    void anIneligibleOptionIsNeverPicked() {
        // Tier 4 peaks at q = 16 (D 45): the nether star (m 0.025) is not eligible, the emerald (m 0.5) is.
        List<List<DemandOption>> ram = List.of(List.of(), List.of(), List.of(),
                List.of(item("minecraft:emerald", 0.5), item("minecraft:nether_star", 0.025)), List.of());
        for (int count = 0; count < 50; count++) {
            List<DemandLine> lines = DemandRoller.roll(MSB, LSB, count, 45, ram);
            assertEquals(1, lines.size());
            assertEquals(List.of("minecraft:emerald"), lines.get(0).ids());
            assertEquals(8, lines.get(0).required());
        }
        // At q 20 (tier 1 at D 10) the same multiplier is eligible: both options come up.
        List<List<DemandOption>> t1 = List.of(List.of(item("a:big", 0.5), item("a:rare", 0.025)));
        Set<String> picked = new HashSet<>();
        for (int count = 0; count < 50; count++) {
            picked.addAll(ids(DemandRoller.roll(MSB, LSB, count, 10, t1)));
        }
        assertEquals(Set.of("a:big", "a:rare"), picked);
    }

    @Test
    void withNothingEligibleTheHighestMultiplierIsAskedForOnce() {
        // q 20 (tier 1 at D 10): 20 * 0.02 = 0.4 and 20 * 0.01 = 0.2, neither eligible
        List<DemandOption> options = List.of(item("a:low", 0.01), item("a:high", 0.02));
        DemandLine line = DemandRoller.pick(new SplittableRandom(1), 20, options);
        assertEquals(List.of("a:high"), line.ids());
        assertEquals(1, line.required());
        assertEquals(0, line.paid());
    }

    @Test
    void countIsTheCeilingOfQTimesM() {
        // Tier 2 at D 10: q = 42.67; wheat 0.5 -> 22 (21.33 up), leather 0.17 -> 8 (7.25 up)
        List<DemandOption> options = List.of(item("minecraft:wheat", 0.5), item("minecraft:leather", 0.17));
        for (int seed = 0; seed < 20; seed++) {
            DemandLine line = DemandRoller.pick(new SplittableRandom(seed), SacrificeCurve.q(2, 10), options);
            int expected = line.ids().equals(List.of("minecraft:wheat")) ? 22 : 8;
            assertEquals(expected, line.required(), line.toString());
        }
    }

    @Test
    void fluidLinesCountMillibuckets() {
        List<List<DemandOption>> god = List.of(List.of(),
                List.of(new DemandOption(DemandLine.Kind.FLUID, List.of("a:diesel"), 0.1)));
        // Tier 2 at D 10: q = 42.67, 0.1 of it is 4.27 -> 5 units -> 5000 mB
        List<DemandLine> lines = DemandRoller.roll(MSB, LSB, 0, 10, god);
        assertEquals(1, lines.size());
        assertEquals(DemandLine.Kind.FLUID, lines.get(0).kind());
        assertEquals(5000, lines.get(0).required());
    }

    @Test
    void dominion10AsksForTiersOneAndTwoOnly() {
        assertEquals(Set.of("t:one", "t:two"), ids(DemandRoller.roll(MSB, LSB, 0, 10, ONE_PER_TIER)));
    }

    @Test
    void dominion30AsksForTiersTwoThreeAndFour() {
        assertEquals(Set.of("t:two", "t:three", "t:four"), ids(DemandRoller.roll(MSB, LSB, 0, 30, ONE_PER_TIER)));
    }

    @Test
    void dominion50AsksForTiersFourAndFive() {
        assertEquals(Set.of("t:four", "t:five"), ids(DemandRoller.roll(MSB, LSB, 0, 50, ONE_PER_TIER)));
    }

    @Test
    void tiersComeOutInOrderOneLineEach() {
        List<DemandLine> lines = DemandRoller.roll(MSB, LSB, 3, 30, AUROCHS);
        assertEquals(3, lines.size());
        assertTrue(Set.of("minecraft:wheat", "minecraft:leather").contains(lines.get(0).ids().get(0)));
        assertTrue(Set.of("createbigcannons:cast_iron_ingot", "farm_and_charm:goulash").contains(lines.get(1).ids().get(0)));
        assertTrue(Set.of("minecraft:blaze_rod", "minecraft:breeze_rod").contains(lines.get(2).ids().get(0)));
    }

    @Test
    void seedsDifferPerTier() {
        assertNotEquals(DemandRoller.seed(MSB, LSB, 0, 1), DemandRoller.seed(MSB, LSB, 0, 2));
        assertNotEquals(DemandRoller.seed(MSB, LSB, 0, 1), DemandRoller.seed(MSB, LSB, 1, 1));
    }

    @Test
    void paymentsAreCappedAtTheRequiredAmount() {
        DemandLine line = item("a:b", 1).line(5);
        assertEquals(5, line.remaining());
        assertEquals(5, line.withPaid(9).paid());
        assertTrue(line.withPaid(5).isPaid());
        assertFalse(DemandLine.allPaid(List.of(line.withPaid(5), line)));
        assertTrue(DemandLine.allPaid(List.of(line.withPaid(5))));
    }
}
