// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

/**
 * Level-3 condition-driven task owned by a Level-2 {@link CivilianSlotRunner}.
 *
 * <p>Evaluated by {@link dev.brights0ng.empire.rules.TaskUtility} at decision boundaries and steps through one or more
 * Level-4 {@link CivilianAction} primitives.
 */
public interface CivilianTask {

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

    /** True if this task runs only during the end-of-shift wrap-up phase (e.g. stowing tools/work gear). */
    default boolean isEndShiftTask() {
        return false;
    }

    default String activeActionName() {
        return "none";
    }
}
