package dev.brights0ng.enginesandempires.oregen;

/**
 * A tiny deterministic random stream for building one deposit. Same seed, same sequence, on every
 * JVM. It exists so deposit generation never depends on {@link java.util.Random}'s or the JDK's
 * implementation details. Not thread-safe: use one per deposit.
 */
public final class DepositRandom {

    private long state;

    public DepositRandom(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        return Hashing.mix64(state);
    }

    /** Uniform in [0, 1). */
    public double nextDouble() {
        return Hashing.unit(nextLong());
    }

    /** Uniform in [low, high). */
    public double range(double low, double high) {
        return low + (high - low) * nextDouble();
    }

    /** Standard normal (mean 0, standard deviation 1), by the Box-Muller method. */
    public double nextGaussian() {
        double u1 = 1.0 - nextDouble(); // in (0, 1], so the log below is finite
        double u2 = nextDouble();
        return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2);
    }
}
