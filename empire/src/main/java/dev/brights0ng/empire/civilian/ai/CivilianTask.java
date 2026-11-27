// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import dev.brights0ng.empire.rules.TaskUtility;

/**
 * Level-3 condition-driven task owned by a Level-2 {@link CivilianSlotRunner}.
 *
 * <p>Evaluated by {@link TaskUtility} at decision boundaries (task completion or interruption) and steps through one
 * or more Level-4 {@link CivilianAction} primitives.
 */
public interface CivilianTask {

    /** Controls how completing or interrupting this task updates Level-2 task momentum. */
    enum MomentumPolicy {
        /** This task becomes the active momentum task (default for primary work tasks). */
        SET,
        /** Support/interruption chore: preserves whatever primary task held momentum before this task ran. */
        PRESERVE,
        /** Passive/idle waiting task: clears momentum so the next real task starts with a fresh comparison. */
        CLEAR
    }

    String name();

    boolean isEligible(AiContext ctx);

    double baseWeight();

    double urgency(AiContext ctx);

    default void start(AiContext ctx) {
    }

    CivilianAction.Status tick(AiContext ctx);

    default void stop(AiContext ctx) {
    }

    /** True when the task is at a clean boundary and may yield to a new Slot or Task (Bright D7). */
    default boolean canYieldCleanly() {
        return true;
    }

    /** How this task affects Level-2 momentum on completion/interruption. */
    default MomentumPolicy momentumPolicy() {
        return MomentumPolicy.SET;
    }

    /** Momentum bonus added by {@link TaskUtility} when this task is the current momentum task. */
    default double momentumBonus() {
        return TaskUtility.DEFAULT_MOMENTUM_BONUS;
    }

    /**
     * True only for passive/idle tasks (e.g. idling by shed) that should periodically check for newly available work.
     * Active work tasks return {@code false} and only trigger Level-2 re-evaluation upon completion or interruption.
     */
    default boolean allowPeriodicRecheck() {
        return false;
    }

    /**
     * Ticks between periodic Level-2 task rechecks when {@link #allowPeriodicRecheck()} is true (or during
     * {@code SOCIALIZE} slots). Defaults to {@link CivilianSlotRunner#TASK_RECHECK_INTERVAL}.
     */
    default int periodicRecheckIntervalTicks() {
        return CivilianSlotRunner.TASK_RECHECK_INTERVAL;
    }

    /** True if this task runs only during the end-of-shift wrap-up phase (e.g. stowing tools/work gear). */
    default boolean isEndShiftTask() {
        return false;
    }

    default String activeActionName() {
        return "none";
    }
}
