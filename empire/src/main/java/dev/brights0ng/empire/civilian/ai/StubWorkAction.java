// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import dev.brights0ng.empire.civilian.CivilianEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

/**
 * Level-4 placeholder work action for an assigned {@link dev.brights0ng.empire.data.WorkAssignment}: tends the work
 * anchor in clean {@value #CYCLE_TICKS}-tick cycles while crediting {@code workDoneTicks} toward today's work quota.
 * Replaced by real job-specific actions (e.g. harvesting, crafting) when workplace regions are built.
 */
public final class StubWorkAction implements CivilianAction {

    /** Clean atomic work cycle length (2 seconds = 40 ticks). */
    public static final int CYCLE_TICKS = 40;

    private final BlockPos workAnchor;
    private int ticks;

    public StubWorkAction(BlockPos workAnchor) {
        this.workAnchor = workAnchor.immutable();
    }

    @Override
    public String name() {
        return "work_cycle";
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
        civ.getLookControl().setLookAt(
                workAnchor.getX() + 0.5, workAnchor.getY() + 0.5, workAnchor.getZ() + 0.5, 30.0F, 30.0F);
        if (ticks == 1 || ticks == CYCLE_TICKS / 2) {
            civ.swing(InteractionHand.MAIN_HAND);
        }
        civ.schedule().addWorkDoneTicks(1);
        return ticks >= CYCLE_TICKS ? Status.SUCCEEDED : Status.RUNNING;
    }

    @Override
    public boolean canYieldCleanly() {
        return ticks >= CYCLE_TICKS;
    }
}
