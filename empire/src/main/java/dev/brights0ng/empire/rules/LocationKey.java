// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Locale;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.CivilianPreferences.Activity;

/**
 * Logical destinations referenced by a civilian's schedule, route memory, and 3-day crowd memory (zero MC imports).
 */
public enum LocationKey {
    HOME("home", false),
    WORK("work", false),
    FOOD_SITE("food_site", false),
    SOCIAL_HUB("social_hub", false),
    HOBBY_FISHING("hobby_fishing", true),
    HOBBY_READING("hobby_reading", false),
    HOBBY_RELAX_HOME("hobby_relax_home", false),
    HOBBY_WALK("hobby_walk", true);

    private final String id;
    private final boolean alwaysOutdoor;

    LocationKey(String id, boolean alwaysOutdoor) {
        this.id = id;
        this.alwaysOutdoor = alwaysOutdoor;
    }

    public String id() {
        return id;
    }

    /** True if this destination is always outdoors and gated to {@link ScheduleClock#isOutdoorTime(int)}. */
    public boolean isAlwaysOutdoor() {
        return alwaysOutdoor;
    }

    public static LocationKey forHobby(Activity activity) {
        return switch (activity) {
            case FISHING -> HOBBY_FISHING;
            case READING -> HOBBY_READING;
            case RELAX_AT_HOME -> HOBBY_RELAX_HOME;
            case WALK -> HOBBY_WALK;
        };
    }

    @Nullable
    public static LocationKey byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (LocationKey key : values()) {
            if (key.id.equals(lower) || key.name().equalsIgnoreCase(lower)) {
                return key;
            }
        }
        return null;
    }
}
