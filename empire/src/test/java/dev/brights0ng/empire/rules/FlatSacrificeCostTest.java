// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class FlatSacrificeCostTest {

    @Test
    void flatCostReturnsFiveBreadForAnyDominion() {
        FlatSacrificeCost cost = FlatSacrificeCost.INSTANCE;
        List<SacrificeCost.Requirement> expected = List.of(new SacrificeCost.Requirement("minecraft:bread", 5));

        assertEquals(expected, cost.costFor(0));
        assertEquals(expected, cost.costFor(10));
        assertEquals(expected, cost.costFor(15));
        assertEquals(expected, cost.costFor(100));
    }
}
