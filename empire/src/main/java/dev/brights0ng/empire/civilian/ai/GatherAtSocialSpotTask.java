// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

/**
 * Level-3 fallback socializing task: lingers at the social destination (Pub / Bonfire) waiting for peers to arrive,
 * crediting slow ambient social progress and yielding immediately when {@link ChatWithNearbyTask} becomes eligible.
 */
public final class GatherAtSocialSpotTask implements CivilianTask {

    private static final int PULSE_TICKS = 40;
    private final WaitIdleAction wait = new WaitIdleAction(PULSE_TICKS);

    @Override
    public String name() {
        return "gather_social";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        return true;
    }

    @Override
    public double baseWeight() {
        return 40.0;
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
        // Slowly chip away at social time while hanging out at the gathering hub, and yield quickly when someone arrives
        ctx.civilian().schedule().addSocialDoneTicks(1);
        return wait.tick(ctx);
    }

    @Override
    public String activeActionName() {
        return wait.name();
    }
}
