package dev.brights0ng.enginesandempires.oregen;

/**
 * Small deterministic hash helpers shared by everything that has to give the same answer on every
 * machine: ore maps, deposit shapes and noise.
 */
public final class Hashing {

    /** SplitMix64 finalizer: a cheap, well-mixed 64-bit hash. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Turns a hash into a number that is uniform in [0, 1). */
    public static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    /** A hash of a seed and a block position. */
    public static long cell(long seed, int x, int y, int z) {
        return mix64(seed
                ^ ((long) x * 0x9E3779B97F4A7C15L)
                ^ ((long) y * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) z * 0xD1B54A32D192ED03L));
    }

    private Hashing() {
    }
}
