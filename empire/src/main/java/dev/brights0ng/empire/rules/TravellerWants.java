// Written by Claude; liked-food matching helper added by Gemini.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * What a traveller wants to be given before it joins an empire: one of these, rolled when it arrives (Bright,
 * 2026-10-24). The counts are provisional.
 */
public final class TravellerWants {

    public record Want(String itemId, int count) {
    }

    public static final List<Want> WANTS = List.of(
            new Want("minecraft:bread", 3),
            new Want("minecraft:baked_potato", 4),
            new Want("minecraft:cooked_porkchop", 2),
            new Want("minecraft:cooked_mutton", 2),
            new Want("minecraft:sweet_berries", 8),
            new Want("minecraft:cooked_cod", 3));

    /** An index into {@link #WANTS}. */
    public static int pick(RandomGenerator random) {
        return random.nextInt(WANTS.size());
    }

    /**
     * Picks a random index into {@link #WANTS} whose item is in {@code favouriteFoods}, or {@code -1} if none match.
     */
    public static int pickMatching(RandomGenerator random, List<String> favouriteFoods) {
        if (favouriteFoods == null || favouriteFoods.isEmpty()) {
            return -1;
        }
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < WANTS.size(); i++) {
            if (favouriteFoods.contains(WANTS.get(i).itemId())) {
                matches.add(i);
            }
        }
        return matches.isEmpty() ? -1 : matches.get(random.nextInt(matches.size()));
    }

    /** The lang key of want {@code i}'s line; its one argument is the count. */
    public static String wantKey(int i) {
        String id = WANTS.get(i).itemId();
        return "civilian.empire.traveller.want." + id.substring(id.indexOf(':') + 1);
    }

    private TravellerWants() {
    }
}
