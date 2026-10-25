// Written by Claude.
package dev.brights0ng.empire.rules;

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

    /** The lang key of want {@code i}'s line; its one argument is the count. */
    public static String wantKey(int i) {
        String id = WANTS.get(i).itemId();
        return "civilian.empire.traveller.want." + id.substring(id.indexOf(':') + 1);
    }

    private TravellerWants() {
    }
}
