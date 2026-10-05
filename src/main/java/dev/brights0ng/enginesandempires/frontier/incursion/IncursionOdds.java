package dev.brights0ng.enginesandempires.frontier.incursion;

/**
 * How likely a settlement is to have an incursion on a given night (Bright's rules, 2026-09-26).
 *
 * <p>A settlement's <em>score</em> adds up how much it draws the Deep's attention: its people (players count 2,
 * villagers, pillagers and colonists 0.25), the thumper shots fired inside it in the last day (a shot's range / 128: a
 * mechanical shot 4, a combustive one 6 to 8), and the loud machines that ran inside it in the last day (each its own
 * weight). The chance rises in a straight line from the tier's minimum at a score of 0 to its maximum at the full score
 * (25), and no further: Settled land 2% to 6%, Civilized land 3% to 10%.
 *
 * <p>Settled land only ever gets lesser incursions. Of a Civilized settlement's incursions, a share (20%) are greater.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class IncursionOdds {

    public enum Kind { NONE, LESSER, GREATER }

    /** What a thumper shot of {@code range} blocks adds to a settlement's score. */
    public static double shotWeight(int range) {
        return range / 128.0;
    }

    /** The nightly chance for a settlement with {@code score}, between {@code min} (score 0) and {@code max} (full score). */
    public static double chance(double score, double fullScore, double min, double max) {
        double t = fullScore <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, score / fullScore));
        return min + (max - min) * t;
    }

    /**
     * What tonight brings a settlement: nothing, a lesser incursion or a greater one.
     *
     * @param chance       the nightly chance ({@link #chance})
     * @param civilized    whether it is Civilized (only Civilized land has greater incursions)
     * @param greaterShare the share of a Civilized settlement's incursions that are greater
     * @param roll         a random number from 0 (inclusive) to 1 (exclusive)
     */
    public static Kind roll(double chance, boolean civilized, double greaterShare, double roll) {
        if (roll >= chance) {
            return Kind.NONE;
        }
        if (civilized && roll < chance * greaterShare) {
            return Kind.GREATER;
        }
        return Kind.LESSER;
    }

    private IncursionOdds() {
    }
}
