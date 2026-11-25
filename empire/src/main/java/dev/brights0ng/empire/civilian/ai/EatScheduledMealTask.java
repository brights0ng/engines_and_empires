// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import dev.brights0ng.empire.civilian.CivilianEntity;

/**
 * Level-3 scheduled meal task: consumes fitting food from pockets (preferring liked foods first) during a {@code MEAL}
 * anchor, finishing cleanly once full or out of fitting food.
 */
public final class EatScheduledMealTask implements CivilianTask {

    private final ConsumeMealAction action = new ConsumeMealAction(true, 4);
    private boolean finished;

    @Override
    public String name() {
        return "eat_meal";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        return !finished && (civ.isUsingItem() || civ.hasFittingPocketFood());
    }

    @Override
    public double baseWeight() {
        return 100.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        finished = false;
        action.start(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        CivilianAction.Status status = action.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            finished = true;
        }
        return status;
    }

    @Override
    public void stop(AiContext ctx) {
        action.stop(ctx);
    }

    @Override
    public boolean canYieldCleanly() {
        return action.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return action.name();
    }
}
