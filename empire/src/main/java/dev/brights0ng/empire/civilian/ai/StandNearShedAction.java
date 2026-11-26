// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import dev.brights0ng.empire.civilian.CivilianEntity;
import net.minecraft.core.BlockPos;

/**
 * Level-4 initial Farmer work action: stands near the farm's seed shed facing the shed in clean
 * {@value #CYCLE_TICKS}-tick cycles while crediting {@code workDoneTicks} toward today's work quota.
 */
public final class StandNearShedAction implements CivilianAction {

    /** Clean yieldable standing cycle length (2 seconds = 40 ticks). */
    public static final int CYCLE_TICKS = 40;

    private final BlockPos shedFocus;
    private int ticks;

    public StandNearShedAction(BlockPos shedFocus) {
        this.shedFocus = shedFocus.immutable();
    }

    @Override
    public String name() {
        return "stand_near_shed";
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
                shedFocus.getX() + 0.5, shedFocus.getY() + 0.5, shedFocus.getZ() + 0.5, 25.0F, 25.0F);
        civ.schedule().addWorkDoneTicks(1);
        return ticks >= CYCLE_TICKS ? Status.SUCCEEDED : Status.RUNNING;
    }

    @Override
    public boolean canYieldCleanly() {
        return ticks >= CYCLE_TICKS;
    }
}
