// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Rolled per-civilian food and recreational activity preferences ({@code docs/empire/civilian-fulfilment.md} §5;
 * Bright, 2026-11-21).
 *
 * <p>Each civilian rolls 1–4 liked food items (from {@code empire:civilian_favourite_foods}, or
 * {@link #DEFAULT_FOOD_POOL} if unavailable) and 1–4 liked recreational activities, with 2–3 most common.
 */
public record CivilianPreferences(List<String> favouriteFoods, List<Activity> favouriteActivities) {

    public enum Activity {
        FISHING("fishing"),
        PUB_CHAT("pub_chat"),
        RELAX_AT_HOME("relax_at_home"),
        WALK("walk");

        private final String id;

        Activity(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Activity byId(String id) {
            if (id == null) {
                return null;
            }
            for (Activity a : values()) {
                if (a.id.equals(id) || a.name().equalsIgnoreCase(id)) {
                    return a;
                }
            }
            return null;
        }
    }

    /** Vanilla fallback pool when tag resolution is unavailable (matches {@code empire:civilian_favourite_foods}). */
    public static final List<String> DEFAULT_FOOD_POOL = List.of(
            "minecraft:cooked_porkchop",
            "minecraft:cooked_mutton",
            "minecraft:cooked_beef",
            "minecraft:cooked_chicken",
            "minecraft:cooked_cod",
            "minecraft:cooked_salmon",
            "minecraft:cooked_rabbit",
            "minecraft:bread",
            "minecraft:baked_potato",
            "minecraft:dried_kelp",
            "minecraft:cookie",
            "minecraft:pumpkin_pie",
            "minecraft:mushroom_stew",
            "minecraft:beetroot_soup",
            "minecraft:rabbit_stew",
            "minecraft:honey_bottle",
            "minecraft:apple",
            "minecraft:melon_slice",
            "minecraft:sweet_berries",
            "minecraft:glow_berries",
            "minecraft:carrot",
            "minecraft:beetroot"
    );

    /** Minimum and maximum rolled preferences per category; 2–3 are weighted 3x more likely than 1 or 4. */
    public static final int MIN_LIKES = 1;
    public static final int MAX_LIKES = 4;

    public CivilianPreferences {
        favouriteFoods = deduplicatedStrings(favouriteFoods);
        favouriteActivities = deduplicatedActivities(favouriteActivities);
    }

    public boolean likesFood(String itemId) {
        return itemId != null && favouriteFoods.contains(itemId);
    }

    public boolean likesActivity(Activity activity) {
        return activity != null && favouriteActivities.contains(activity);
    }

    public boolean isEmpty() {
        return favouriteFoods.isEmpty() && favouriteActivities.isEmpty();
    }

    /** Rolls 1–4 distinct favourite foods from {@code foodPool} (fallback {@link #DEFAULT_FOOD_POOL}) and 1–4 activities. */
    public static CivilianPreferences roll(RandomGenerator rng, List<String> foodPool) {
        Objects.requireNonNull(rng);
        List<String> pool = deduplicatedStrings(foodPool == null || foodPool.isEmpty() ? DEFAULT_FOOD_POOL : foodPool);
        if (pool.isEmpty()) {
            pool = DEFAULT_FOOD_POOL;
        }

        int foodCount = Math.min(rollCount(rng), pool.size());
        List<String> foodCandidates = new ArrayList<>(pool);
        List<String> pickedFoods = new ArrayList<>(foodCount);
        for (int i = 0; i < foodCount; i++) {
            int idx = rng.nextInt(foodCandidates.size());
            pickedFoods.add(foodCandidates.remove(idx));
        }

        Activity[] allActivities = Activity.values();
        int actCount = Math.min(rollCount(rng), allActivities.length);
        List<Activity> actCandidates = new ArrayList<>(List.of(allActivities));
        List<Activity> pickedActivities = new ArrayList<>(actCount);
        for (int i = 0; i < actCount; i++) {
            int idx = rng.nextInt(actCandidates.size());
            pickedActivities.add(actCandidates.remove(idx));
        }

        return new CivilianPreferences(pickedFoods, pickedActivities);
    }

    /** Deterministic fallback preferences seeded from a civilian UUID (for older saves without preferences). */
    public static CivilianPreferences defaultFor(UUID id) {
        long seed = id.getMostSignificantBits() ^ (id.getLeastSignificantBits() * 31L) ^ 0x5EED_F00DL;
        return roll(new Random(seed), DEFAULT_FOOD_POOL);
    }

    /**
     * Rolls a count in 1..4 with weights (1, 3, 3, 1): 12.5% for 1, 37.5% for 2, 37.5% for 3, 12.5% for 4.
     */
    static int rollCount(RandomGenerator rng) {
        int r = rng.nextInt(8);
        if (r == 0) return 1;
        if (r <= 3) return 2;
        if (r <= 6) return 3;
        return 4;
    }

    private static List<String> deduplicatedStrings(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String s : input) {
            if (s != null && !s.isBlank()) {
                seen.add(s);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(seen));
    }

    private static List<Activity> deduplicatedActivities(List<Activity> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        Set<Activity> seen = new LinkedHashSet<>();
        for (Activity a : input) {
            if (a != null) {
                seen.add(a);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(seen));
    }
}
