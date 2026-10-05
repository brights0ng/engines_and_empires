package dev.brights0ng.enginesandempires.frontier.tier;

/**
 * What the tier rules need to know about the sections around one section.
 *
 * @param anchors        anchor blocks (beds, lanterns, workstations, hay bales) in the area
 * @param habitation     how long anyone has been about in the area (kept per section for the area around it; a crowd
 *                       counts the same as one person)
 * @param recentResident whether a villager, colonist or illager has been in the area recently
 * @param freeGuards     guards in the area that are free to move (always 0 until guards are tracked)
 */
public record AreaCounts(int anchors, long habitation, boolean recentResident, int freeGuards) {

    public static final AreaCounts EMPTY = new AreaCounts(0, 0, false, 0);
}
