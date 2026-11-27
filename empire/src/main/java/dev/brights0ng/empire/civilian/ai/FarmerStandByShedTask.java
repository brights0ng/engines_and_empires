// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.FarmerUtility;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.Occupation;
import net.minecraft.core.BlockPos;

/**
 * Level-3 initial Farmer task: walks to a standable spot beside the assigned farm's seed shed and stands near it
 * during work hours until the daily work quota finishes, a scheduled anchor approaches, or the farm becomes invalid.
 */
public final class FarmerStandByShedTask implements CivilianTask {

    /** Maximum distance from the resolved shed stand spot before walking closer. */
    private static final double STAND_REACH = 2.25;

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
    public void start(AiContext ctx) {
        BlockPos standSpot = locations.resolve(ctx, LocationKey.WORK);
        if (standSpot == null) {
            currentAction = null;
            return;
        }
        if (!standSpot.closerToCenterThan(ctx.civilian().position(), STAND_REACH)) {
            currentAction = new MoveToAction(standSpot, STAND_REACH, null, null);
        } else {
            BlockPos focus = locations.resolveFarmShedFocus(ctx);
            currentAction = new StandNearShedAction(focus != null ? focus : standSpot);
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
            if (wasMove && status == CivilianAction.Status.SUCCEEDED) {
                BlockPos standSpot = locations.resolve(ctx, LocationKey.WORK);
                if (standSpot == null) {
                    return CivilianAction.Status.FAILED;
                }
                BlockPos focus = locations.resolveFarmShedFocus(ctx);
                currentAction = new StandNearShedAction(focus != null ? focus : standSpot);
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
        return currentAction == null || currentAction.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "stand_near_shed";
    }
}
