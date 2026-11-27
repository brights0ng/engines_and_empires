// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.FarmerUtility;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.Occupation;
import net.minecraft.core.BlockPos;

/**
 * Level-3 fallback Farmer task: returns to the assigned farm's seed shed area when there is no immediate work and
 * idles naturally around the shed (looking around, strolling within a 5–6 block radius off farmland, and chatting with
 * nearby civilians) while {@link CivilianSlotRunner} rechecks for new field work every 15 seconds.
 */
public final class FarmerStandByShedTask implements CivilianTask {

    /** Distance threshold beyond which the farmer walks back toward the shed before idling around it. */
    private static final double RETURN_TRIGGER_DIST = IdleNearShedAction.LOITER_RADIUS;
    /** Arrival reach when walking back to the shed stand spot. */
    private static final double RETURN_REACH = 3.0;

    private final LocationResolver locations;
    @Nullable
    private CivilianAction currentAction;

    public FarmerStandByShedTask(LocationResolver locations) {
        this.locations = locations;
    }

    @Override
    public String name() {
        return "farmer_stand_by_shed";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        WorkAssignment work = ctx.civilian().workAssignment();
        return work != null
                && work.isOccupation()
                && work.occupation() == Occupation.FARMER
                && locations.resolve(ctx, LocationKey.WORK) != null;
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_WAIT_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public MomentumPolicy momentumPolicy() {
        return MomentumPolicy.CLEAR;
    }

    @Override
    public boolean allowPeriodicRecheck() {
        return true;
    }

    @Override
    public int periodicRecheckIntervalTicks() {
        return FarmerUtility.IDLE_WORK_RECHECK_TICKS;
    }

    @Override
    public void start(AiContext ctx) {
        BlockPos standSpot = locations.resolve(ctx, LocationKey.WORK);
        if (standSpot == null) {
            currentAction = null;
            return;
        }
        if (!standSpot.closerToCenterThan(ctx.civilian().position(), RETURN_TRIGGER_DIST)) {
            currentAction = new MoveToAction(standSpot, RETURN_REACH, null, null);
        } else {
            BlockPos focus = locations.resolveFarmShedFocus(ctx);
            currentAction = new IdleNearShedAction(standSpot, focus != null ? focus : standSpot);
        }
        currentAction.start(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            start(ctx);
            if (currentAction == null) {
                return CivilianAction.Status.FAILED;
            }
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            currentAction.stop(ctx);
            boolean wasMove = currentAction instanceof MoveToAction;
            currentAction = null;
            if (wasMove) {
                BlockPos standSpot = locations.resolve(ctx, LocationKey.WORK);
                if (standSpot == null) {
                    return CivilianAction.Status.FAILED;
                }
                BlockPos focus = locations.resolveFarmShedFocus(ctx);
                currentAction = new IdleNearShedAction(standSpot, focus != null ? focus : standSpot);
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            return status;
        }
        return CivilianAction.Status.RUNNING;
    }

    @Override
    public void stop(AiContext ctx) {
        if (currentAction != null) {
            currentAction.stop(ctx);
            currentAction = null;
        }
    }

    @Override
    public boolean canYieldCleanly() {
        return true;
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "idle_near_shed";
    }
}
