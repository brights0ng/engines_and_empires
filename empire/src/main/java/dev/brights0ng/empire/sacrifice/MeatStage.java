// Written by Claude.
package dev.brights0ng.empire.sacrifice;

/** How fresh meat is, as the gods judge it (Simple Spoilage's stages, when it is installed). */
public enum MeatStage {
    FRESH,
    RIPE,
    STALE,
    ROTTING;

    /** Fresh or ripe: what every god but one that accepts rotting meat insists on for its founding meat. */
    public boolean isFresh() {
        return this == FRESH || this == RIPE;
    }
}
