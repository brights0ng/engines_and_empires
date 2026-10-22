// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.List;

public final class FlatSacrificeCost implements SacrificeCost {

    public static final FlatSacrificeCost INSTANCE = new FlatSacrificeCost();
    public static final String DEFAULT_ITEM = "minecraft:bread";
    public static final int DEFAULT_COUNT = 5;

    private static final List<Requirement> COST = List.of(new Requirement(DEFAULT_ITEM, DEFAULT_COUNT));

    @Override
    public List<Requirement> costFor(int dominion) {
        return COST;
    }
}
