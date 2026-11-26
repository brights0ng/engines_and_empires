// Written by Gemini.
package dev.brights0ng.empire.rules;

import javax.annotation.Nullable;

/**
 * Pure occupation definitions for Empire citizens (zero Minecraft imports).
 *
 * <p>Each employed occupation binds to a compatible {@link RegionType} workplace, specifies whether its shift is
 * indoor or outdoor (gating outdoor work to {@link ScheduleClock#OUTDOOR_START_TICK}..{@link ScheduleClock#OUTDOOR_END_TICK}),
 * and supplies its default daily work ask.
 */
public enum Occupation {
    UNEMPLOYED("unemployed", null, false, 0),
    FARMER("farmer", RegionType.FARM, false, CivilianSchedule.DEFAULT_WORK_ASK_TICKS);

    private final String id;
    @Nullable
    private final RegionType workplaceType;
    private final boolean indoor;
    private final int defaultDailyAskTicks;

    Occupation(String id, @Nullable RegionType workplaceType, boolean indoor, int defaultDailyAskTicks) {
        this.id = id;
        this.workplaceType = workplaceType;
        this.indoor = indoor;
        this.defaultDailyAskTicks = defaultDailyAskTicks;
    }

    public String id() {
        return id;
    }

    /** Translation key for the player-facing occupation title (e.g. {@code occupation.empire.farmer}). */
    public String langKey() {
        return "occupation.empire." + id;
    }

    @Nullable
    public RegionType workplaceType() {
        return workplaceType;
    }

    public boolean indoor() {
        return indoor;
    }

    public int defaultDailyAskTicks() {
        return defaultDailyAskTicks;
    }

    public boolean isEmployed() {
        return this != UNEMPLOYED;
    }

    @Nullable
    public static Occupation byId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Occupation occupation : values()) {
            if (occupation.id.equalsIgnoreCase(id) || occupation.name().equalsIgnoreCase(id)) {
                return occupation;
            }
        }
        return null;
    }

    /** Returns the occupation staffed by {@code regionType}, or {@code null} if that region type takes no workers. */
    @Nullable
    public static Occupation forRegionType(@Nullable RegionType regionType) {
        if (regionType == null) {
            return null;
        }
        for (Occupation occupation : values()) {
            if (occupation.workplaceType == regionType) {
                return occupation;
            }
        }
        return null;
    }
}
