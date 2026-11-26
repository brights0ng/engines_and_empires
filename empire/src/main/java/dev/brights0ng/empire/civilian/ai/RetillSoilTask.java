// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;

/**
 * Level-3 Farmer task: re-tills trampled or reverted soil inside {@code FIELD} rooms back into farmland using a hoe
 * via {@link InteractBlockAction#till(BlockPos)}.
 */
public final class RetillSoilTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    @Nullable
    private BlockPos targetSoil;
    @Nullable
    private CivilianAction currentAction;

    public RetillSoilTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_retill_soil";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        return snap.valid() && !snap.revertedDirt().isEmpty() && FarmWorkScanner.hasHoe(ctx.civilian());
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_RETILL_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        return FarmerUtility.retillUrgency(scanner.scan(ctx).revertedDirt().size());
    }

    @Override
    public void start(AiContext ctx) {
        targetSoil = null;
        currentAction = null;
        beginRetill(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            return beginRetill(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.FAILED;
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            currentAction.stop(ctx);
            boolean wasMove = currentAction instanceof MoveToAction;
            currentAction = null;
            if (wasMove) {
                if (status != CivilianAction.Status.SUCCEEDED || targetSoil == null) {
                    return CivilianAction.Status.FAILED;
                }
                currentAction = InteractBlockAction.till(targetSoil);
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            scanner.invalidate();
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
        targetSoil = null;
        EquipAction.stowMainHand(ctx.civilian());
    }

    @Override
    public boolean canYieldCleanly() {
        return currentAction == null || currentAction.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "retill_soil";
    }

    private boolean beginRetill(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.revertedDirt().isEmpty() || !FarmWorkScanner.hasHoe(ctx.civilian())) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        BlockPos civPos = civ.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : snap.revertedDirt()) {
            double d2 = pos.distSqr(civPos);
            if (best == null || d2 < bestDistSq) {
                best = pos;
                bestDistSq = d2;
            }
        }
        if (best == null) {
            return false;
        }
        targetSoil = best;
        double reach = FarmWorkScanner.workReach(civ);
        if (!best.closerToCenterThan(civ.position(), reach)) {
            currentAction = new MoveToAction(best.above(), reach, null, null);
        } else {
            currentAction = InteractBlockAction.till(best);
        }
        currentAction.start(ctx);
        return true;
    }
}
