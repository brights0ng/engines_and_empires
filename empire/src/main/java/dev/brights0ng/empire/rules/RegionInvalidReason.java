// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Locale;

/** Why a region failed scanning or validation ({@link #NONE} when valid). */
public enum RegionInvalidReason {
    NONE,
    TOO_SMALL,
    TOO_LARGE,
    TOO_SHORT,
    NO_BED,
    EXPOSED_TO_SKY,
    TOO_DARK,
    HAZARD,
    UNSTAKED,
    OVERLAPS_EXISTING;

    private final String id = name().toLowerCase(Locale.ROOT);

    public String id() {
        return id;
    }

    public static RegionInvalidReason byId(String id) {
        if (id == null) {
            return NONE;
        }
        for (RegionInvalidReason reason : values()) {
            if (reason.id.equals(id)) {
                return reason;
            }
        }
        return NONE;
    }
}
