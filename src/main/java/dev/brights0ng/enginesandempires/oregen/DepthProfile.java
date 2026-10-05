package dev.brights0ng.enginesandempires.oregen;

/**
 * Where, vertically, deposits of one ore sit, and how their size depends on depth.
 *
 * <p>Each deposit picks a height uniformly within the ore's range. Deposits at or above the profile's
 * origin height are the ore's normal size. Below it they grow: each deep deposit draws a multiplier
 * between {@code deepMin} and {@code deepMax}, reached in full {@link #RAMP} blocks below the origin, so
 * there is no sudden jump at a single height.
 *
 * <p>The origin is {@link #ORIGIN_Y} (y=0) unless a profile says otherwise. A realm with no heights
 * below that, like the Nether, can use {@link #deepThroughout(int, int)} to make every deposit deep-sized.
 *
 * @param minY    lowest height a deposit's centre may be drawn at
 * @param maxY    highest height a deposit's centre may be drawn at
 * @param deepMin the smallest size multiplier a fully deep deposit can get
 * @param deepMax the largest size multiplier a fully deep deposit can get
 * @param originY deposits centred below this height grow; at or above it they are normal size
 */
public record DepthProfile(int minY, int maxY, double deepMin, double deepMax, int originY) {

    /** Lowest block of the overworld. */
    public static final int WORLD_MIN_Y = -64;
    /** Highest block of the overworld. */
    public static final int WORLD_MAX_Y = 319;
    /** Deposits are kept above the bedrock layer. */
    public static final int BEDROCK_TOP = -59;
    /** Below this height deposits normally grow. */
    public static final int ORIGIN_Y = 0;
    /** How many blocks below the origin a deposit takes to reach its full deep multiplier. */
    public static final int RAMP = 16;

    /** Normal-size at and above {@link #ORIGIN_Y}, growing 4 to 8 times below it. */
    public DepthProfile(int minY, int maxY) {
        this(minY, maxY, 4.0, 8.0);
    }

    /** Normal-size at and above {@link #ORIGIN_Y}, growing between {@code deepMin} and {@code deepMax} times below it. */
    public DepthProfile(int minY, int maxY, double deepMin, double deepMax) {
        this(minY, maxY, deepMin, deepMax, ORIGIN_Y);
    }

    /**
     * A profile in which every deposit is deep-sized (4 to 8 times normal), whatever height it is at. Its
     * origin sits just above the top of the range, far enough that even the highest deposit has reached
     * its full multiplier.
     */
    public static DepthProfile deepThroughout(int minY, int maxY) {
        return new DepthProfile(minY, maxY, 4.0, 8.0, maxY + RAMP + 1);
    }

    public DepthProfile {
        if (minY < BEDROCK_TOP || maxY > WORLD_MAX_Y || maxY < minY) {
            throw new IllegalArgumentException("need " + BEDROCK_TOP + " <= minY <= maxY <= " + WORLD_MAX_Y);
        }
        if (deepMin < 1.0 || deepMax < deepMin) {
            throw new IllegalArgumentException("need 1 <= deepMin <= deepMax");
        }
    }

    /** Draws the height of a deposit's centre. */
    int pickY(DepositRandom rng) {
        return minY + (int) Math.floor(rng.nextDouble() * (maxY - minY + 1));
    }

    /**
     * How many times bigger than usual a deposit centred at height {@code y} is.
     *
     * @param roll a number in [0, 1) drawn for this deposit, choosing where in the deep range it falls
     */
    double sizeMultiplier(int y, double roll) {
        if (y >= originY) {
            return 1.0;
        }
        double target = deepMin + (deepMax - deepMin) * roll;
        double t = Math.min(1.0, (originY - y) / (double) RAMP);
        double smooth = t * t * (3.0 - 2.0 * t);
        return 1.0 + (target - 1.0) * smooth;
    }

    /** The largest multiplier any deposit of this ore can get. */
    double maxMultiplier() {
        return minY < originY ? deepMax : 1.0;
    }

    /** True if a deposit drawn at this height is shallow: normal-sized by depth, and open to biome factors. */
    public boolean isShallow(int y) {
        return y >= originY;
    }

    /**
     * The share of this ore's expected ore blocks that sit in shallow deposits, before any biome factor.
     * Heights are drawn uniformly and deep deposits are bigger, so this is much smaller than the share of
     * the height range that is shallow. Used to judge how much a biome factor changes the ore's total.
     */
    public double shallowOreShare() {
        double shallow = 0.0;
        double total = 0.0;
        for (int y = minY; y <= maxY; y++) {
            double size = sizeMultiplier(y, 0.5); // linear in the roll, so its mean roll gives its mean size
            total += size;
            if (isShallow(y)) {
                shallow += size;
            }
        }
        return total == 0.0 ? 0.0 : shallow / total;
    }

    /** The same profile with a different top to its height range. */
    public DepthProfile withMaxY(int newMaxY) {
        return new DepthProfile(minY, newMaxY, deepMin, deepMax, originY);
    }

    /**
     * The average size multiplier of a deposit of this ore, over all the heights it can be drawn at, when its
     * shallow deposits are scaled by {@code shallowFactor}. 1 would mean "normal shallow size on average".
     */
    public double meanSize(double shallowFactor) {
        double total = 0.0;
        for (int y = minY; y <= maxY; y++) {
            total += isShallow(y) ? shallowFactor : sizeMultiplier(y, 0.5); // linear in the roll, so the mean roll gives the mean
        }
        return total / (maxY - minY + 1);
    }

    /** The shallow factor that gives this profile a {@link #meanSize} of {@code target}. */
    public double shallowFactorFor(double target) {
        int shallow = 0;
        double deep = 0.0;
        for (int y = minY; y <= maxY; y++) {
            if (isShallow(y)) {
                shallow++;
            } else {
                deep += sizeMultiplier(y, 0.5);
            }
        }
        if (shallow == 0) {
            throw new IllegalStateException("No shallow heights to scale");
        }
        return (target * (maxY - minY + 1) - deep) / shallow;
    }
}
