package dev.brights0ng.enginesandempires.frontier.deep;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The disturbance thumping makes: too many shots too close together call up an incursion from below.
 *
 * <p>Shots are counted over the 3×3 chunks around each one, within a sliding window (5 shots in a minute by default), so
 * spreading thumpers one chunk apart does not get around it. Dry fires are not counted. When the count is reached, the
 * count starts over and a chance is rolled for a (lesser) incursion: 10% if every counted shot was mechanical, 25% if every
 * one was combustive, and in between by the share of combustive shots. If it comes up, the 3×3 chunks around go quiet for
 * the cooldown (30 minutes by default), so the next shots do not call up another.
 *
 * <p>Nothing here touches Minecraft. Chunks are given by their coordinates.
 */
public final class Disturbance {

    /** What a shot did: nothing yet, reached the count but the roll missed, or called up an incursion. */
    public enum Outcome { NONE, MISSED, INCURSION }

    private record Shot(long time, boolean combustive) {
    }

    private final Map<Long, ArrayDeque<Shot>> shots = new HashMap<>();
    private final Map<Long, Long> quietUntil = new HashMap<>();

    /**
     * Records a shot in chunk {@code (cx, cz)} at {@code now}, and says what it calls up.
     *
     * @param mechanicalChance the incursion chance when every counted shot was mechanical
     * @param combustiveChance the incursion chance when every counted shot was combustive
     * @param roll             a random number from 0 (inclusive) to 1 (exclusive), for that chance
     */
    public Outcome record(int cx, int cz, long now, ShotSource source, int needed, int window, int cooldown,
                          double mechanicalChance, double combustiveChance, double roll) {
        if (!source.isRealShot()) {
            return Outcome.NONE;
        }
        prune(now, window);
        shots.computeIfAbsent(key(cx, cz), k -> new ArrayDeque<>()).add(new Shot(now, source == ShotSource.COMBUSTIVE));
        if (isQuiet(cx, cz, now)) {
            return Outcome.NONE;
        }
        int count = 0;
        int combustive = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ArrayDeque<Shot> here = shots.get(key(cx + dx, cz + dz));
                if (here == null) {
                    continue;
                }
                for (Shot shot : here) {
                    count++;
                    if (shot.combustive()) {
                        combustive++;
                    }
                }
            }
        }
        if (count < needed) {
            return Outcome.NONE;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                shots.remove(key(cx + dx, cz + dz));
            }
        }
        if (roll >= chance(count, combustive, mechanicalChance, combustiveChance)) {
            return Outcome.MISSED;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                quietUntil.put(key(cx + dx, cz + dz), now + cooldown);
            }
        }
        return Outcome.INCURSION;
    }

    /** The incursion chance for {@code count} shots of which {@code combustive} were combustive. */
    public static double chance(int count, int combustive, double mechanicalChance, double combustiveChance) {
        double share = count <= 0 ? 0.0 : (double) combustive / count;
        return mechanicalChance + (combustiveChance - mechanicalChance) * share;
    }

    /** Shots counted around chunk {@code (cx, cz)} right now (for the debug command). */
    public int countAround(int cx, int cz, long now, int window) {
        prune(now, window);
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ArrayDeque<Shot> here = shots.get(key(cx + dx, cz + dz));
                count += here == null ? 0 : here.size();
            }
        }
        return count;
    }

    /** Whether chunk {@code (cx, cz)} is in its cooldown. */
    public boolean isQuiet(int cx, int cz, long now) {
        Long until = quietUntil.get(key(cx, cz));
        return until != null && now < until;
    }

    public long quietUntil(int cx, int cz) {
        return quietUntil.getOrDefault(key(cx, cz), Long.MIN_VALUE);
    }

    private void prune(long now, int window) {
        for (Iterator<ArrayDeque<Shot>> it = shots.values().iterator(); it.hasNext(); ) {
            ArrayDeque<Shot> here = it.next();
            while (!here.isEmpty() && now - here.peekFirst().time() >= window) {
                here.pollFirst();
            }
            if (here.isEmpty()) {
                it.remove();
            }
        }
        quietUntil.values().removeIf(until -> until <= now);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
