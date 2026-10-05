package dev.brights0ng.enginesandempires.oregen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers recently built deposit bodies. A deposit that straddles chunk borders is rebuilt by every
 * chunk it touches, and the game builds neighbouring chunks close together in time, so a small cache
 * saves repeating the work. Purely an optimisation: a miss just rebuilds the identical body.
 *
 * <p>Thread-safe. Bodies are keyed by deposit seed, which is unique per world, ore and location, and by
 * attempt, since a deposit whose first draw was rejected has a different body for each later one, and by
 * biome factor, which is fixed for a deposit in a world but is mixed in so a mismatch can never be served.
 */
public final class BodyCache {

    private static final int CAPACITY = 64;

    private final Map<Long, DepositBody> entries = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, DepositBody> eldest) {
            return size() > CAPACITY;
        }
    };

    /** The deposit's ordinary body (attempt 0). */
    public DepositBody get(Deposit deposit, OreType type) {
        return get(deposit, type, 0, 1.0);
    }

    /** The body of one attempt at the deposit, with no biome influence. */
    public DepositBody get(Deposit deposit, OreType type, int attempt) {
        return get(deposit, type, attempt, 1.0);
    }

    /** The body of one attempt at the deposit, for its biome factor. */
    public DepositBody get(Deposit deposit, OreType type, int attempt, double biomeFactor) {
        return get(key(deposit, attempt, biomeFactor, BiomeRule.KEEP_HEIGHT),
                () -> DepositBody.generate(deposit, type, attempt, biomeFactor));
    }

    /** The body of one attempt at the deposit, under the biome rule that applies to it (null for none). */
    public DepositBody get(Deposit deposit, OreType type, int attempt, BiomeRule rule) {
        return get(key(deposit, attempt, rule), () -> DepositBody.generate(deposit, type, attempt, rule));
    }

    private DepositBody get(long key, java.util.function.Supplier<DepositBody> build) {
        synchronized (entries) {
            DepositBody cached = entries.get(key);
            if (cached != null) {
                return cached;
            }
        }
        DepositBody body = build.get(); // built outside the lock so others are not held up
        synchronized (entries) {
            entries.put(key, body);
        }
        return body;
    }

    /** Remembers a body that has already been built, so it does not have to be built again. */
    public void put(Deposit deposit, int attempt, DepositBody body) {
        put(deposit, attempt, 1.0, body);
    }

    /** Remembers a body built for a biome factor. */
    public void put(Deposit deposit, int attempt, double biomeFactor, DepositBody body) {
        synchronized (entries) {
            entries.put(key(deposit, attempt, biomeFactor, BiomeRule.KEEP_HEIGHT), body);
        }
    }

    /** Remembers a body built under a biome rule (null for none). */
    public void put(Deposit deposit, int attempt, BiomeRule rule, DepositBody body) {
        synchronized (entries) {
            entries.put(key(deposit, attempt, rule), body);
        }
    }

    /** How many bodies are currently remembered. */
    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private static long key(Deposit deposit, int attempt, BiomeRule rule) {
        return rule == null
                ? key(deposit, attempt, 1.0, BiomeRule.KEEP_HEIGHT)
                : key(deposit, attempt, rule.factor(), rule.maxY());
    }

    private static long key(Deposit deposit, int attempt, double biomeFactor, int maxY) {
        long key = attempt == 0 ? deposit.seed() : Hashing.mix64(deposit.seed() + attempt * 0x9E3779B97F4A7C15L);
        if (biomeFactor != 1.0) {
            key = Hashing.mix64(key ^ Double.doubleToLongBits(biomeFactor));
        }
        if (maxY != BiomeRule.KEEP_HEIGHT) {
            key = Hashing.mix64(key ^ (0xC2B2AE3D27D4EB4FL * (maxY + 1L)));
        }
        return key;
    }
}
