// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import dev.brights0ng.empire.civilian.CivilianEntity;

/** Level-4 gentle idle/relax pulse at a comfortable spot. */
public final class WaitIdleAction implements CivilianAction {

    private final int durationTicks;
    private int ticks;

    public WaitIdleAction(int durationTicks) {
        this.durationTicks = Math.max(10, durationTicks);
    }

    @Override
    public String name() {
        return "wait_idle";
    }

    @Override
    public void start(AiContext ctx) {
        ticks = 0;
        ctx.civilian().getNavigation().stop();
    }

    @Override
    public Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        ticks++;
        if (ticks == 1) {
            double angle = civ.getRandom().nextDouble() * Math.PI * 2.0;
            civ.getLookControl().setLookAt(
                    civ.getX() + Math.cos(angle) * 4.0, civ.getEyeY(), civ.getZ() + Math.sin(angle) * 4.0, 15.0F, 15.0F);
        }
        return ticks >= durationTicks ? Status.SUCCEEDED : Status.RUNNING;
    }
}
