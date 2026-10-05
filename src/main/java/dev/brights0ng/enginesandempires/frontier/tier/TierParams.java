package dev.brights0ng.enginesandempires.frontier.tier;

/**
 * Every number the tier rules use, as one value, so the rules can be tested without a running server. The live values
 * come from {@link dev.brights0ng.enginesandempires.frontier.FrontierConfig}.
 *
 * <p>Times are in game ticks (24,000 to a day). Distances are in subchunks (16×16×16 sections), measured as a cube: "within
 * 4" means no more than 4 sections away along any axis.
 *
 * @param settleTicks       inhabited time an area needs before it can be Settled
 * @param graceTicks        how long an area keeps its inhabited time with nobody there before it starts to fade
 * @param decayPerTick      inhabited ticks lost per tick once the grace is over (1 = a day's worth per day)
 * @param habitationCap     most inhabited time one section can bank
 * @param areaRadius        how far the Settled and Civilized checks look around a section
 * @param ringRadius        how close to Settled land a section must be to count as Uninhabited rather than Frontier
 * @param nearRadius        how close to Settled land counts as "next to it", for faster settling
 * @param nearMultiplier    how much faster inhabited time builds up next to Settled land
 * @param minAnchors        how many anchor blocks (beds, lanterns, workstations...) an area needs to be Settled
 * @param civilizedGuards   how many free guards an area needs to be Civilized
 * @param frontierBelowY    everything below this y is Frontier
 * @param uninhabitedBelowY everything below this y is at best Uninhabited
 */
public record TierParams(int settleTicks, long graceTicks, double decayPerTick, int habitationCap, int areaRadius,
                         int ringRadius, int nearRadius, int nearMultiplier, int minAnchors, int civilizedGuards,
                         int frontierBelowY, int uninhabitedBelowY) {

    public static final int DAY = 24_000;

    /** The values Bright chose (2026-09-25). */
    public static final TierParams DEFAULTS = new TierParams(
            DAY * 3 / 2,   // 1.5 days to settle
            DAY * 7L,      // 7 days' grace
            1.0,           // then a day's worth lost per day
            DAY * 3 / 2,   // a section banks at most one settle's worth
            4, 8, 4, 4,
            1, 5,
            0, 54);
}
