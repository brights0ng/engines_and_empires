// Written by Claude.
package dev.brights0ng.empire.rules;

/**
 * How hungry an empire's god is (Bright's design, 2026-10-23). Hunger counts the ticks since the god was last fed, only
 * while a member is online. From {@link #WARN_FRACTION} of the limit (one in-game year by default) the god is hungry and
 * puts out the main bonfire every {@link #EXTINGUISH_INTERVAL_TICKS}; at the limit it abandons the empire. A limit of 0
 * or less means the god never grows hungry.
 */
public final class GodSatiety {

    public enum State { SATED, HUNGRY, ABANDONED }

    /** The share of the limit from which the god is hungry. */
    public static final double WARN_FRACTION = 0.75;
    /** How often a hungry god puts out a lit main bonfire, ticks of hunger (15 s). */
    public static final int EXTINGUISH_INTERVAL_TICKS = 300;

    /** The hunger from which the god is hungry: {@link #WARN_FRACTION} of {@code limit}, rounded up. */
    public static long warnTicks(long limit) {
        return (long) Math.ceil(limit * WARN_FRACTION);
    }

    public static State state(long hunger, long limit) {
        if (limit <= 0) {
            return State.SATED;
        }
        if (hunger >= limit) {
            return State.ABANDONED;
        }
        return hunger >= warnTicks(limit) ? State.HUNGRY : State.SATED;
    }

    /** Whether hunger going from {@code before} to {@code after} crosses into hunger (true for one step only). */
    public static boolean crossedWarning(long before, long after, long limit) {
        if (limit <= 0) {
            return false;
        }
        long warn = warnTicks(limit);
        return before < warn && after >= warn;
    }

    /**
     * Whether the god puts the bonfire out on this step: it is hungry after it, and it has just become hungry or hunger
     * has passed a multiple of {@link #EXTINGUISH_INTERVAL_TICKS}.
     */
    public static boolean extinguishDue(long before, long after, long limit) {
        return state(after, limit) == State.HUNGRY && (crossedWarning(before, after, limit)
                || Math.floorDiv(before, EXTINGUISH_INTERVAL_TICKS) != Math.floorDiv(after, EXTINGUISH_INTERVAL_TICKS));
    }

    private GodSatiety() {
    }
}
