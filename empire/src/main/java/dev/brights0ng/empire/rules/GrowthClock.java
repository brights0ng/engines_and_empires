// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The last game time each staked chunk was growing (its crops getting random ticks), for the catch-up of the civilian
 * chunk-loading plan (§3.1). Keyed by {@link ChunkKeys} chunk; a chunk belongs to one empire, so one clock serves all.
 * A chunk with no entry has not been seen growing since it was staked: its first sighting pays nothing back.
 */
public final class GrowthClock {

    /** Returned by {@link #last} for a chunk with no entry. */
    public static final long NEVER = -1L;

    private final Map<Long, Long> lastGrowing = new HashMap<>();

    /**
     * Records that {@code chunk} is growing at {@code now}, seen by a scan every {@code scanInterval} ticks. Returns the
     * ticks it missed since it last grew: none on its first sighting or while it grew at every scan; otherwise the time
     * between the two sightings less one scan interval (it stopped and started again somewhere within an interval).
     */
    public long markGrowing(long chunk, long now, long scanInterval) {
        Long last = lastGrowing.put(chunk, now);
        if (last == null || now <= last) {
            return 0L;
        }
        return Math.max(0L, now - last - scanInterval);
    }

    /** The last time {@code chunk} was growing, or {@link #NEVER}. */
    public long last(long chunk) {
        return lastGrowing.getOrDefault(chunk, NEVER);
    }

    /** Sets the last growing time (loading, tests). */
    public void set(long chunk, long time) {
        lastGrowing.put(chunk, time);
    }

    /** Puts back {@code previous} if {@code chunk}'s time is still {@code expected} (a catch-up that could not run). */
    public void restore(long chunk, long expected, long previous) {
        if (last(chunk) == expected) {
            if (previous == NEVER) {
                lastGrowing.remove(chunk);
            } else {
                lastGrowing.put(chunk, previous);
            }
        }
    }

    /** Drops {@code chunk}'s time (staked afresh, unstaked, or its empire disbanded). */
    public void forget(long chunk) {
        lastGrowing.remove(chunk);
    }

    public int size() {
        return lastGrowing.size();
    }

    /** Every chunk's time, for saving. */
    public Map<Long, Long> entries() {
        return Collections.unmodifiableMap(lastGrowing);
    }
}
