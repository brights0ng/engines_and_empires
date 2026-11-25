// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

/** Level-3 relax task: idles comfortably at the current or designated relaxation spot. */
public final class RelaxTask implements CivilianTask {

    private final WaitIdleAction wait = new WaitIdleAction(60);

    @Override
    public String name() {
        return "relax";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        return true;
    }

    @Override
    public double baseWeight() {
        return 30.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        wait.start(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        return wait.tick(ctx);
    }

    @Override
    public String activeActionName() {
        return wait.name();
    }
}
