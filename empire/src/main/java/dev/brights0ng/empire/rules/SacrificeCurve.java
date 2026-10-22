// Written by Claude.
package dev.brights0ng.empire.rules;

/**
 * How much of each tier a god asks for at a given dominion D (Bright's sacrifice curve). Each tier ramps up linearly from
 * its start to its peak quantity; tiers 1-3 then taper linearly to nothing at their end, while tiers 4 and 5 never end and
 * keep growing past the peak as a square root.
 */
public final class SacrificeCurve {

    public static final int TIERS = 5;

    private static final double[] START = {0, 5, 17, 28, 38};
    private static final double[] PEAK = {12, 20, 30, 45, 60};
    /** Where tiers 1-3 reach zero again; tiers 4 and 5 never do. */
    private static final double[] END = {27, 37, 50, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    private static final double[] Q_MAX = {24, 128, 90, 16, 48};

    /** The quantity q of {@code tier} (1-5) at dominion {@code d}; 0 when the tier is not asked for. */
    public static double q(int tier, double d) {
        if (tier < 1 || tier > TIERS) {
            throw new IllegalArgumentException("tier must be 1-" + TIERS + ", is " + tier);
        }
        int i = tier - 1;
        double start = START[i];
        double peak = PEAK[i];
        double qMax = Q_MAX[i];
        if (d <= start) {
            return 0;
        }
        if (d <= peak) {
            return qMax * (d - start) / (peak - start);
        }
        double end = END[i];
        if (Double.isInfinite(end)) {
            return qMax * Math.sqrt((d - start) / (peak - start));
        }
        if (d >= end) {
            return 0;
        }
        return qMax * (end - d) / (end - peak);
    }

    private SacrificeCurve() {
    }
}
