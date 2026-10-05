package dev.brights0ng.enginesandempires.geophone;

import java.util.Arrays;

import dev.brights0ng.enginesandempires.oregen.Hashing;

/**
 * How good a reading's fix on a deposit is, and how far off from the truth that lets it be.
 *
 * <p>A reader still gets ground truth handed to it directly by the geophones (see {@link ReaderRecorder}) rather than working
 * a position out from arrival times the way a real seismic survey would. What this adds is an amount of error on top of that
 * truth, which depends on two things and nothing else (not the kind of geophone, not whether a wind-up reader or a smart
 * logger took it, not how many geophones, not how far away the deposit is):
 * <ul>
 *   <li><b>What made the vibration.</b> Every source has a worst case, {@link #maxErrorFor}: the range of the next source
 *       down. A plate struck by hand is off by at most {@code 16} blocks, the reach of a hammer on bare ground; a mechanical
 *       thumper by at most {@code 128}, a struck plate's reach. So a bigger shot finds a deposit roughly, and the smaller
 *       one below it can always be used to close in on it. The combustive thumper counts as a single source whatever it
 *       burns: gasoline and diesel shots are both off by at most {@code 512}, the mechanical thumper's reach, so a player
 *       never needs one fuel to close in on what the other found.</li>
 *   <li><b>How far apart the geophones were staked.</b> An array no wider than {@link #FULL_ERROR_SPREAD} gets the worst
 *       case; a wider one divides it down, so doubling the spread halves the error. It never gets worse than the worst
 *       case, however tight the cluster.</li>
 * </ul>
 *
 * <p>The blur applied is a deterministic function of the deposit and the exact set of geophones used, not a fresh roll of the
 * dice: firing the same shot through the same array twice gives the same reading both times, the way a real, repeatable
 * measurement error would. The only way to do better is to change the array, not to try again, so spamming the same shot buys
 * nothing.
 *
 * <p>The error figure itself is not stored with a reading, only a coarse tag, {@link Confidence}.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class ReaderAccuracy {

    /**
     * An array of geophones this spread out or tighter (see {@link #spreadOf}) gets the full worst-case error of its shot.
     * Wider ones divide it down: twice as wide, half the error.
     */
    public static final double FULL_ERROR_SPREAD = 3.0;

    /**
     * The rungs of the accuracy ladder, from the weakest source up: a hammer on bare ground, a struck plate (or a thumper's
     * dud drop), and the mechanical thumper. A shot's worst-case error is the highest rung below its range. The combustive
     * thumper is deliberately not a rung: its fuels differ in reach, not in which source they step down to, so every
     * combustive shot (gasoline, biodiesel, diesel or volatile) lands on the mechanical thumper's range.
     */
    static final int[] SHOT_RANGES = {SeismicShots.HAMMER_RANGE, SeismicShots.PLATE_RANGE, SeismicShots.MECHANICAL_RANGE};

    /** The worst-case error of the weakest shot there is, a hammer on bare ground, which has nothing below it. */
    public static final double LOWEST_MAX_ERROR = 4.0;

    /** At or below this many blocks of error, a reading is called precise. */
    public static final double PRECISE_MAX_BLOCKS = 8.0;

    /** At or below this many blocks of error (and above {@link #PRECISE_MAX_BLOCKS}), a reading is called approximate. Above it, rough. */
    public static final double APPROXIMATE_MAX_BLOCKS = 25.0;

    /** How much smaller the vertical blur is than the horizontal one: height is coarser to begin with. */
    public static final double VERTICAL_FACTOR = 0.5;

    /** How good a reading is, in terms a player can act on rather than a number they would have to interpret. */
    public enum Confidence {
        /** No figure was ever worked out for this reading: one saved before this was tracked. Treated as the worst case. */
        UNKNOWN,
        ROUGH,
        APPROXIMATE,
        PRECISE
    }

    /**
     * How spread out a set of positions is: the distance a typical one of them sits from their shared centre. Zero if they
     * all sit in the same place, or if there is only one (or none).
     */
    public static double spreadOf(double[] xs, double[] zs) {
        if (xs.length == 0) {
            return 0.0;
        }
        double centreX = 0.0;
        double centreZ = 0.0;
        for (int i = 0; i < xs.length; i++) {
            centreX += xs[i];
            centreZ += zs[i];
        }
        centreX /= xs.length;
        centreZ /= xs.length;
        double sumSquares = 0.0;
        for (int i = 0; i < xs.length; i++) {
            double dx = xs[i] - centreX;
            double dz = zs[i] - centreZ;
            sumSquares += dx * dx + dz * dz;
        }
        return Math.sqrt(sumSquares / xs.length);
    }

    /**
     * The most a reading from a vibration of this range can be off by, in blocks: the range of the next weaker source (see
     * {@link #SHOT_RANGES}), or {@link #LOWEST_MAX_ERROR} for the weakest of all.
     */
    public static double maxErrorFor(int range) {
        double below = LOWEST_MAX_ERROR;
        for (int shot : SHOT_RANGES) {
            if (shot < range) {
                below = Math.max(below, shot);
            }
        }
        return below;
    }

    /**
     * How far off a reading taken through an array this spread out could be, in blocks, when its shot's worst case is
     * {@code maxErrorBlocks}: the worst case for an array up to {@link #FULL_ERROR_SPREAD} wide, and shrinking in proportion
     * as the array widens. Never more than the worst case.
     */
    public static double errorRadius(double spreadBlocks, double maxErrorBlocks) {
        return maxErrorBlocks * FULL_ERROR_SPREAD / Math.max(spreadBlocks, FULL_ERROR_SPREAD);
    }

    /** The coarse tag a player sees for an error figure. */
    public static Confidence confidenceFor(double errorRadiusBlocks) {
        if (errorRadiusBlocks <= PRECISE_MAX_BLOCKS) {
            return Confidence.PRECISE;
        }
        return errorRadiusBlocks <= APPROXIMATE_MAX_BLOCKS ? Confidence.APPROXIMATE : Confidence.ROUGH;
    }

    /**
     * A seed for {@link #jitterOffset}, from the deposit and the exact geophones used. Order does not matter: the same set of
     * geophones always gives the same seed, however it happens to be listed.
     */
    public static long arraySeed(long depositId, long[] geophoneKeys) {
        long[] sorted = geophoneKeys.clone();
        Arrays.sort(sorted);
        long hash = Hashing.mix64(depositId);
        for (long key : sorted) {
            hash = Hashing.mix64(hash ^ key);
        }
        return hash;
    }

    /**
     * How far off, in each direction, a reading with this seed and this much possible error actually lands. Repeatable: the
     * same seed always gives the same answer. {@code dy} is smaller than {@code dx}/{@code dz} by {@link #VERTICAL_FACTOR},
     * matching how height is coarser to begin with. An error of zero (or less) gives no offset at all. Each part is rounded
     * towards zero, so the offset is never further than the error allows.
     *
     * @return {dx, dy, dz}, in whole blocks
     */
    public static int[] jitterOffset(long seed, double errorRadiusBlocks) {
        if (!(errorRadiusBlocks > 0.0)) {
            return new int[]{0, 0, 0};
        }
        long angleHash = Hashing.mix64(seed);
        long radiusHash = Hashing.mix64(angleHash);
        long verticalHash = Hashing.mix64(radiusHash);

        double angle = Hashing.unit(angleHash) * 2.0 * Math.PI;
        // Linear in the random fraction, not the square root of it as true uniform-disk sampling would need: this weights
        // most readings a little closer to the truth than the worst case, with a long tail out to the full radius.
        double radius = errorRadiusBlocks * Hashing.unit(radiusHash);
        double dx = Math.cos(angle) * radius;
        double dz = Math.sin(angle) * radius;
        double dy = (Hashing.unit(verticalHash) - 0.5) * 2.0 * errorRadiusBlocks * VERTICAL_FACTOR;

        return new int[]{(int) dx, (int) dy, (int) dz};
    }

    private ReaderAccuracy() {
    }
}
