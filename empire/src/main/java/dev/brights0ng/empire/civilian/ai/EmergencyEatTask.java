// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import dev.brights0ng.empire.civilian.CivilianEntity;

/**
 * Level-3 interrupt task: immediately eats fitting food from pockets when hunger drops to or below the citizen's
 * emergency threshold outside scheduled mealtime.
 */
public final class EmergencyEatTask implements CivilianTask {

    private final ConsumeMealAction action = new ConsumeMealAction(true, 2);

    @Override
    public String name() {
        return "emergency_eat";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        return civ.isUsingItem() || civ.hasFittingPocketFood();
    }

    @Override
    public double baseWeight() {
        return 180.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        ctx.civilian().getNavigation().stop();
        action.start(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        return action.tick(ctx);
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
