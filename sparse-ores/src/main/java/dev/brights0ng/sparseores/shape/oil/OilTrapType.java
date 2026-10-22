// Written by Gemini.
package dev.brights0ng.sparseores.shape.oil;

/**
 * The geological trap structure of an oil reservoir.
 */
public enum OilTrapType {
    ANTICLINE("Anticlinal Fold", "anticline"),
    FAULT_WEDGE("Fault-Bounded Trap", "fault_wedge"),
    RIBBON("Sinuous Paleochannel", "ribbon"),
    CRESCENT("Salt Dome Flank", "crescent"),
    MOUND("Lenticular Reef Mound", "mound"),
    SHEET("Continuous Shale Basin", "sheet");

    private final String displayName;
    private final String id;

    OilTrapType(String displayName, String id) {
        this.displayName = displayName;
        this.id = id;
    }

    public String displayName() {
        return displayName;
    }

    public String id() {
        return id;
    }

    public static OilTrapType byId(String id) {
        for (OilTrapType type : values()) {
            if (type.id.equalsIgnoreCase(id) || type.name().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return ANTICLINE;
    }
}
