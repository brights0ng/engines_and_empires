package dev.brights0ng.enginesandempires.oregen;

/**
 * How many ore blocks a typical deposit of one ore holds, and how much that varies from deposit to
 * deposit. These are the numbers for a shallow deposit; deeper ones are scaled up by the ore's
 * {@link DepthProfile}.
 *
 * @param median the middle of the distribution: half of all deposits hold fewer ore blocks than this
 * @param sigma  how widely sizes spread around the median (log scale). About 0.35 means a typical
 *               deposit is within roughly a third bigger or smaller than the median, with rare large
 *               and small ones.
 * @param min    hard floor on the number of ore blocks
 * @param max    hard cap on the number of ore blocks
 */
public record SizeProfile(int median, double sigma, int min, int max) {

    public SizeProfile {
        if (min < 1 || max < min) {
            throw new IllegalArgumentException("need 1 <= min <= max");
        }
        if (median < min || median > max) {
            throw new IllegalArgumentException("median must lie between min and max");
        }
        if (!(sigma >= 0.0)) {
            throw new IllegalArgumentException("sigma must not be negative");
        }
    }

    /** Draws a deposit size, in ore blocks, from this profile. */
    int draw(DepositRandom rng) {
        long size = Math.round(median * StrictMath.exp(sigma * rng.nextGaussian()));
        return (int) Math.max(min, Math.min(max, size));
    }
}
