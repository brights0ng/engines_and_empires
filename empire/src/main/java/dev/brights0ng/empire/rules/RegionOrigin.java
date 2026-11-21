// Written by Gemini.
package dev.brights0ng.empire.rules;

import javax.annotation.Nullable;

/** How an empire region was created (governs auto-deletion after expiry). */
public enum RegionOrigin {
    AUTO("auto"),
    MANUAL("manual");

    private final String id;

    RegionOrigin(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Nullable
    public static RegionOrigin byId(String id) {
        if (id == null) {
            return null;
        }
        for (RegionOrigin origin : values()) {
            if (origin.id.equals(id)) {
                return origin;
            }
        }
        return null;
    }
}
