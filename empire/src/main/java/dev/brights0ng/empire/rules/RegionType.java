// Written by Gemini.
package dev.brights0ng.empire.rules;

import javax.annotation.Nullable;

/** Purpose of an empire region. */
public enum RegionType {
    HOME("home");

    private final String id;

    RegionType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Nullable
    public static RegionType byId(String id) {
        if (id == null) {
            return null;
        }
        for (RegionType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
