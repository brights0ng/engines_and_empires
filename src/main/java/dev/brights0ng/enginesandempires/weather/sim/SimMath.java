package dev.brights0ng.enginesandempires.weather.sim;

/** Small shared helpers for the weather systems: deterministic hashing and smooth steps. Pure. */
public final class SimMath {

    /** A well-mixed 64-bit hash of {@code v} (SplitMix64's finaliser). */
    public static long mix(long v) {
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    /** A hash of several values. */
    public static long hash(long seed, long... values) {
        long h = mix(seed ^ 0x9E3779B97F4A7C15L);
        for (long v : values) {
            h = mix(h ^ (v * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L));
        }
        return h;
    }

    /** A number in [0, 1) from hash {@code h} and {@code salt}. */
    public static double unit(long h, int salt) {
        return (mix(h + salt * 0xD1B54A32D192ED03L) >>> 11) * 0x1.0p-53;
    }

    public static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    /** Smoothstep of {@code t} clamped to [0, 1]. */
    public static double smooth(double t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    private SimMath() {
    }
}
