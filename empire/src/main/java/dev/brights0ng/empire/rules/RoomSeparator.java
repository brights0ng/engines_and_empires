// Written by Gemini.
package dev.brights0ng.empire.rules;

/**
 * Block categories that can act as room separators inside a region.
 * Separator blocks belong to neither room they border, regardless of open/closed blockstate.
 */
public enum RoomSeparator {
    DOOR,
    TRAPDOOR,
    FENCE_GATE
}
