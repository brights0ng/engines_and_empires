// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Learned pairwise travel-time memory between logical {@link LocationKey}s (zero Minecraft imports).
 *
 * <p>Unseen routes fall back to straight-line distance divided by expected walking speed times {@link #DETOUR_FACTOR}.
 * Completed trips smooth into the cached estimate via an exponential moving average ({@link #EMA_ALPHA}), reacting
 * naturally to obstacles (e.g. a creeper crater) and recovering once repaired without letting a single freak snag
 * poison the estimate indefinitely. If a location's anchor moves by more than {@link #ANCHOR_DRIFT_SQ} squared blocks
 * (e.g. claiming a new home), cached pairs touching that location are invalidated automatically.
 */
public final class RouteMemory {

    /** PROVISIONAL: straight-line detour multiplier for unseen routes. */
    public static final double DETOUR_FACTOR = 1.25;
    /** PROVISIONAL: exponential moving average weight for newly observed trip durations. */
    public static final double EMA_ALPHA = 0.50;
    /** PROVISIONAL: expected horizontal walk speed (blocks/tick) at {@link CivilianDerivedStats#BASE_MOVEMENT_SPEED}. */
    public static final double BASE_WALK_BLOCKS_PER_TICK = 0.145;
    /** Minimum non-zero travel estimate between two distinct locations (1 second). */
    public static final int MIN_TRAVEL_TICKS = 20;
    /** Maximum multiplier above baseline allowed for a single trip sample (outlier clamp). */
    public static final int MAX_OUTLIER_MULTIPLIER = 4;
    /** Minimum outlier cap (30 seconds) so short routes still absorb realistic detours. */
    public static final int MIN_OUTLIER_CAP_TICKS = 600;
    /** Squared block distance beyond which an anchor move invalidates cached routes for that location (8 blocks). */
    public static final long ANCHOR_DRIFT_SQ = 64L;
    /** Horizontal distance within which two coordinates are considered co-located (2 blocks). */
    private static final double SAME_SPOT_DISTANCE = 2.0;

    /** Canonical unordered pair of two distinct {@link LocationKey}s. */
    public record Pair(LocationKey a, LocationKey b) {
        public Pair {
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            if (a == b) {
                throw new IllegalArgumentException("RouteMemory.Pair requires distinct locations: " + a);
            }
            if (a.ordinal() > b.ordinal()) {
                LocationKey tmp = a;
                a = b;
                b = tmp;
            }
        }

        public boolean involves(LocationKey key) {
            return a == key || b == key;
        }
    }

    private final Map<Pair, Integer> estimates = new LinkedHashMap<>();
    private final EnumMap<LocationKey, Long> anchors = new EnumMap<>(LocationKey.class);

    /** Updates the known anchor position for {@code key}, clearing stale pairs if it moved significantly. */
    public void updateAnchor(LocationKey key, long packedPos) {
        Objects.requireNonNull(key, "key");
        Long prev = anchors.get(key);
        if (prev != null && squaredDistance(prev, packedPos) > ANCHOR_DRIFT_SQ) {
            estimates.keySet().removeIf(pair -> pair.involves(key));
        }
        anchors.put(key, packedPos);
    }

    /** Clears all cached routes involving {@code key} and forgets its anchor. */
    public void invalidate(LocationKey key) {
        Objects.requireNonNull(key, "key");
        anchors.remove(key);
        estimates.keySet().removeIf(pair -> pair.involves(key));
    }

    /** Returns the learned travel estimate in ticks for {@code (from, to)}, or {@code -1} if unseen. */
    public int learnedTicks(LocationKey from, LocationKey to) {
        if (from == to) {
            return 0;
        }
        Integer val = estimates.get(new Pair(from, to));
        return val != null ? val : -1;
    }

    /**
     * Estimates travel ticks from {@code fromPos} (optionally tagged with {@code from}) to {@code toPos}
     * ({@code to}), preferring learned route memory when both keys are known and distinct.
     */
    public int estimateTicks(@Nullable LocationKey from, LocationKey to,
                             long fromPackedPos, long toPackedPos, double movementSpeed) {
        Objects.requireNonNull(to, "to");
        if (from == to) {
            double d = distance(fromPackedPos, toPackedPos);
            return d <= SAME_SPOT_DISTANCE * 2.0 ? 0 : baselineTicks(fromPackedPos, toPackedPos, movementSpeed);
        }
        if (from != null) {
            Integer learned = estimates.get(new Pair(from, to));
            if (learned != null) {
                return learned;
            }
        }
        return baselineTicks(fromPackedPos, toPackedPos, movementSpeed);
    }

    /** Straight-line baseline estimate scaled by {@code movementSpeed} and {@link #DETOUR_FACTOR}. */
    public static int baselineTicks(long fromPackedPos, long toPackedPos, double movementSpeed) {
        double dist = distance(fromPackedPos, toPackedPos);
        if (dist <= SAME_SPOT_DISTANCE) {
            return 0;
        }
        double speedRatio = Double.isFinite(movementSpeed) && movementSpeed > 0.0
                ? movementSpeed / CivilianDerivedStats.BASE_MOVEMENT_SPEED
                : 1.0;
        double blocksPerTick = Math.max(0.05, BASE_WALK_BLOCKS_PER_TICK * speedRatio);
        return Math.max(MIN_TRAVEL_TICKS, (int) Math.ceil((dist / blocksPerTick) * DETOUR_FACTOR));
    }

    /** Records an observed trip duration between two distinct locations, smoothing with {@link #EMA_ALPHA}. */
    public void recordTrip(LocationKey from, LocationKey to, int actualTicks, int baselineTicks) {
        if (from == null || to == null || from == to || actualTicks <= 0) {
            return;
        }
        int cap = Math.max(MIN_OUTLIER_CAP_TICKS, Math.max(MIN_TRAVEL_TICKS, baselineTicks) * MAX_OUTLIER_MULTIPLIER);
        int clamped = Math.clamp(actualTicks, MIN_TRAVEL_TICKS, cap);
        Pair pair = new Pair(from, to);
        Integer prev = estimates.get(pair);
        if (prev == null) {
            estimates.put(pair, clamped);
        } else {
            int smoothed = (int) Math.round(prev * (1.0 - EMA_ALPHA) + clamped * EMA_ALPHA);
            estimates.put(pair, Math.max(MIN_TRAVEL_TICKS, smoothed));
        }
    }

    public Map<Pair, Integer> estimates() {
        return Collections.unmodifiableMap(estimates);
    }

    public Map<LocationKey, Long> anchors() {
        return Collections.unmodifiableMap(anchors);
    }

    public Saved save() {
        return new Saved(
                Collections.unmodifiableMap(new LinkedHashMap<>(estimates)),
                Collections.unmodifiableMap(new EnumMap<>(anchors))
        );
    }

    public static RouteMemory restore(Saved saved) {
        RouteMemory mem = new RouteMemory();
        if (saved != null) {
            for (Map.Entry<Pair, Integer> entry : saved.estimates().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    mem.estimates.put(entry.getKey(), Math.max(MIN_TRAVEL_TICKS, entry.getValue()));
                }
            }
            for (Map.Entry<LocationKey, Long> entry : saved.anchors().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    mem.anchors.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return mem;
    }

    public RouteMemory copy() {
        return restore(save());
    }

    private static double distance(long a, long b) {
        return Math.sqrt(squaredDistance(a, b));
    }

    private static long squaredDistance(long a, long b) {
        long dx = BlockKeys.getX(a) - BlockKeys.getX(b);
        long dy = BlockKeys.getY(a) - BlockKeys.getY(b);
        long dz = BlockKeys.getZ(a) - BlockKeys.getZ(b);
        return dx * dx + dy * dy + dz * dz;
    }

    public record Saved(Map<Pair, Integer> estimates, Map<LocationKey, Long> anchors) {
        public static final Saved EMPTY = new Saved(Map.of(), Map.of());
    }
}
