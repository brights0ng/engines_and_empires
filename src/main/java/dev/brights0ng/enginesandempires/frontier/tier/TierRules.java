package dev.brights0ng.enginesandempires.frontier.tier;

import dev.brights0ng.enginesandempires.frontier.Tier;

/**
 * Decides tiers. Worked out in two steps:
 * <ol>
 *   <li>A section's <em>base</em> tier comes from what is around it: {@link Tier#SETTLED} if the area has an anchor block and
 *       enough inhabited time (or a recent resident), {@link Tier#CIVILIZED} if it is Settled and guarded, otherwise
 *       {@link Tier#FRONTIER}. This is what is cached.</li>
 *   <li>The tier at a block then {@linkplain #resolve applies the rest}: the dimension, Uninhabited land around Settled land,
 *       and the depth limits, which work per block because y 54 is not a section boundary.</li>
 * </ol>
 *
 * <p>Nothing here touches Minecraft.
 */
public final class TierRules {

    /** The base tier of a section, from the counts in the area around it. */
    public static Tier base(AreaCounts area, TierParams params) {
        boolean inhabited = area.habitation() >= params.settleTicks() || area.recentResident();
        boolean settled = area.anchors() >= params.minAnchors() && inhabited;
        if (!settled) {
            return Tier.FRONTIER;
        }
        return area.freeGuards() >= params.civilizedGuards() ? Tier.CIVILIZED : Tier.SETTLED;
    }

    /**
     * The tier at a block.
     *
     * @param overworld      whether the block is in the Overworld (every other dimension is Uninhabited everywhere)
     * @param base           the base tier of its section
     * @param settledNearby  whether Settled or Civilized land is within {@link TierParams#ringRadius()}
     * @param y              the block's height
     */
    public static Tier resolve(boolean overworld, Tier base, boolean settledNearby, int y, TierParams params) {
        if (!overworld) {
            return Tier.UNINHABITED;
        }
        if (y < params.frontierBelowY()) {
            return Tier.FRONTIER;
        }
        Tier tier = base == Tier.FRONTIER && settledNearby ? Tier.UNINHABITED : base;
        if (y < params.uninhabitedBelowY()) {
            tier = tier.atMost(Tier.UNINHABITED);
        }
        return tier;
    }

    /**
     * Whether a section counts as Settled land for the land around it (making it Uninhabited, and settling faster). A
     * section counts only if it is Settled at its top block, so a bed in a mine does not tame the land around the mine.
     *
     * @param sectionTopY the y of the section's top layer of blocks
     */
    public static boolean isSettledSource(Tier base, int sectionTopY, TierParams params) {
        return base.atLeast(Tier.SETTLED) && sectionTopY >= params.frontierBelowY()
                && sectionTopY >= params.uninhabitedBelowY();
    }

    private TierRules() {
    }
}
