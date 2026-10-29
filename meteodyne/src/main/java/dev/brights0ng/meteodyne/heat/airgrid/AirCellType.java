// Written by Claude.
package dev.brights0ng.meteodyne.heat.airgrid;

/**
 * What one block is to the air-cell grid (local-air redesign). Pure Java: the study and its tests use it without
 * Minecraft. {@link AirCellClassifier} maps block states to these.
 */
public enum AirCellType {
    /** Open air, or a block too small to matter. */
    AIR,
    /** A wall: air neither enters nor passes. */
    SOLID,
    /** Solid in its lower half; air in the upper half. */
    BOTTOM_HALF,
    /** Solid in its upper half; air in the lower half. */
    TOP_HALF,
    /** A thin post or pane (fences, walls, panes, chains): air passes around it. */
    THIN,
    /** Water or lava: a wall for air (lava becomes a heat source later). */
    FLUID,
    /** Air passes through slowly (leaves, scaffolding, cobwebs, iron bars). */
    PERMEABLE,
    /** Doors, trapdoors and fence gates; open or closed becomes a face flag later. */
    DOOR;

    private static final AirCellType[] VALUES = values();

    /** Whether the grid simulates this block as a cell; SOLID and FLUID are walls. */
    public boolean simulated() {
        return this != SOLID && this != FLUID;
    }

    public static AirCellType of(int ordinal) {
        return VALUES[ordinal];
    }
}
