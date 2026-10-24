// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OfferingKeysTest {

    private static final String RAW_MEAT = "c:foods/raw_meat";

    private static DemandOption option(DemandLine.Kind kind, String... ids) {
        return new DemandOption(kind, List.of(ids), 0.5);
    }

    @Test
    void collectsEveryKindOfOptionAcrossTiers() {
        List<List<DemandOption>> tiers = List.of(
                List.of(option(DemandLine.Kind.ITEM, "minecraft:beef")),
                List.of(option(DemandLine.Kind.ITEMS, "minecraft:cod", "naturalist:catfish"),
                        option(DemandLine.Kind.TAG, "minecraft:logs")),
                List.of(option(DemandLine.Kind.FLUID, "createdieselgenerators:diesel")));

        OfferingKeys keys = OfferingKeys.of(tiers, false, RAW_MEAT);

        assertEquals(Set.of("minecraft:beef", "minecraft:cod", "naturalist:catfish"), keys.items());
        assertEquals(Set.of("minecraft:logs"), keys.tags());
        assertEquals(Set.of("createdieselgenerators:diesel"), keys.fluids());
    }

    @Test
    void rawMeatTagCountsOnlyWhenSomeGodTakesRottingMeat() {
        List<List<DemandOption>> tiers = List.of(List.of(option(DemandLine.Kind.ITEM, "minecraft:rotten_flesh")));

        assertFalse(OfferingKeys.of(tiers, false, RAW_MEAT).tags().contains(RAW_MEAT));
        assertTrue(OfferingKeys.of(tiers, true, RAW_MEAT).tags().contains(RAW_MEAT));
    }

    @Test
    void noGodsTakeNothing() {
        assertEquals(OfferingKeys.EMPTY, OfferingKeys.of(List.of(), false, RAW_MEAT));
    }
}
