package dev.brights0ng.enginesandempires.frontier.tier;

/**
 * Inhabited time: how long people (players, villagers, colonists, illagers) have spent in a section, and how it fades
 * when they stop coming.
 *
 * <p>A section stores the inhabited time it had when someone was last there, and when that was. How much is left now is
 * worked out when it is asked for, so nothing has to tick while nobody is around, and time passes while the chunk is
 * unloaded too: an outpost left alone for a fortnight is found gone cold.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class Habitation {

    /** Marks a time that never happened (no resident has ever been seen). */
    public static final long NEVER = Long.MIN_VALUE;

    /** What is left of {@code stored} ticks of inhabited time, last topped up at {@code lastSeen}, at time {@code now}. */
    public static int effective(int stored, long lastSeen, long now, TierParams params) {
        if (stored <= 0 || lastSeen == NEVER) {
            return Math.max(0, stored);
        }
        long idle = now - lastSeen - params.graceTicks();
        if (idle <= 0) {
            return stored;
        }
        double lost = idle * params.decayPerTick();
        return lost >= stored ? 0 : (int) (stored - lost);
    }

    /** The stored value after {@code amount} ticks more are spent there at {@code now} (what had faded is gone first). */
    public static int add(int stored, long lastSeen, long now, int amount, TierParams params) {
        long total = (long) effective(stored, lastSeen, now, params) + amount;
        return (int) Math.max(0, Math.min(params.habitationCap(), total));
    }

    /** Whether a resident (a villager, colonist or illager) seen at {@code residentSeen} still counts at {@code now}. */
    public static boolean recentResident(long residentSeen, long now, TierParams params) {
        return residentSeen != NEVER && now - residentSeen <= params.graceTicks();
    }

    /**
     * How much inhabited time to credit someone being about at {@code now}, when the section was last credited at
     * {@code lastCredited}: the time that has passed since, but no more than {@code upTo} (how often people are counted).
     * A second person counted in the same stretch adds only what has passed since the first, so a crowd counts the same as
     * one person.
     */
    public static long elapsedSince(long lastCredited, long now, int upTo) {
        if (lastCredited == NEVER) {
            return upTo;
        }
        return Math.max(0, Math.min(upTo, now - lastCredited));
    }

    private Habitation() {
    }
}
