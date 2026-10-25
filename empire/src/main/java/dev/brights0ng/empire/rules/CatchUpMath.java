// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.function.DoubleSupplier;

/**
 * How many random ticks a block gets back for the time its chunk missed (civilian chunk-loading plan, §3.1). Vanilla
 * gives each block of a running section {@code randomTickSpeed} / 4096 random ticks per game tick on average, so a gap
 * of {@code g} ticks is worth a Poisson number of ticks with mean {@code g * randomTickSpeed / 4096}, capped.
 */
public final class CatchUpMath {

    /** Blocks in a chunk section; each random tick picks one of them. */
    public static final int SECTION_BLOCKS = 4096;
    /** Most ticks given back to a crop-like block per catch-up (provisional, P3). */
    public static final int CROP_CAP = 64;
    /** Most ticks given back to a Dynamic Trees soil or sapling per catch-up; their growth steps are heavier (P3). */
    public static final int TREE_CAP = 32;

    /** The mean number of random ticks one block gets in {@code gapTicks} game ticks. */
    public static double lambda(long gapTicks, int randomTickSpeed) {
        if (gapTicks <= 0 || randomTickSpeed <= 0) {
            return 0.0;
        }
        return (double) gapTicks * randomTickSpeed / SECTION_BLOCKS;
    }

    /**
     * A Poisson draw with mean {@code lambda}, capped at {@code cap}, by inversion from one uniform draw in [0, 1). A
     * mean so large that every value below the cap is vanishingly unlikely returns the cap.
     */
    public static int ticks(double lambda, int cap, DoubleSupplier uniform) {
        if (lambda <= 0.0 || cap <= 0) {
            return 0;
        }
        double u = uniform.getAsDouble();
        double p = Math.exp(-lambda);
        double cumulative = p;
        int k = 0;
        while (cumulative <= u && k < cap) {
            k++;
            p *= lambda / k;
            cumulative += p;
        }
        return k;
    }

    private CatchUpMath() {
    }
}
