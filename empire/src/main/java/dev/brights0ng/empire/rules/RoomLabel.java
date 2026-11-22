// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Locale;

/**
 * Labels assigned to rooms inside a region.
 */
public enum RoomLabel {
    INDOOR,
    OUTDOOR;

    private final String id = name().toLowerCase(Locale.ROOT);

    public String id() {
        return id;
    }

    public static RoomLabel byId(String id) {
        if (id == null) {
            return null;
        }
        for (RoomLabel label : values()) {
            if (label.id.equals(id)) {
                return label;
            }
        }
        return null;
    }
}
