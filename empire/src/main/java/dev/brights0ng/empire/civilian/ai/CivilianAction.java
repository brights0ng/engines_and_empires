// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

/**
 * Level-4 atomic Minecraft action primitive executed by a {@link CivilianTask}.
 */
public interface CivilianAction {

    enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    String name();

    default void start(AiContext ctx) {
    }

    Status tick(AiContext ctx);

    default void stop(AiContext ctx) {
    }

    /**
     * False while mid-atomic operation (e.g. chewing food or mid-work cycle) so Level 2 lets the action complete
     * cleanly before switching slots (Bright D7).
     */
    default boolean canYieldCleanly() {
        return true;
    }
}
