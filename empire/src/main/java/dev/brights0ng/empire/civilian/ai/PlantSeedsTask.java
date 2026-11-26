// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.CropBlock;

/**
 * Level-3 Farmer task: plants seeds into empty farmland inside {@code FIELD} rooms using each room's target crop
 * (player override, majority crop, or first available seed for fresh fields).
 */
public final class PlantSeedsTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    @Nullable
    private FarmWorkScanner.EmptyPlotTarget currentTarget;
    @Nullable
    private CivilianAction currentAction;

    public PlantSeedsTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_plant_seeds";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        return snap.valid() && countPlantablePlots(ctx.civilian(), snap) > 0;
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_PLANT_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        return FarmerUtility.plantUrgency(countPlantablePlots(ctx.civilian(), scanner.scan(ctx)));
    }

    @Override
    public void start(AiContext ctx) {
        currentTarget = null;
        currentAction = null;
        beginPlant(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            return beginPlant(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.FAILED;
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            currentAction.stop(ctx);
            boolean wasMove = currentAction instanceof MoveToAction;
            currentAction = null;
            if (wasMove) {
                if (status != CivilianAction.Status.SUCCEEDED || currentTarget == null || currentTarget.targetSeed() == null) {
                    return CivilianAction.Status.FAILED;
                }
                currentAction = createPlantAction(currentTarget.cropPos(), currentTarget.targetSeed());
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            scanner.invalidate();
            EquipAction.stowMainHand(ctx.civilian());
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
        currentTarget = null;
        EquipAction.stowMainHand(ctx.civilian());
    }

    @Override
    public boolean canYieldCleanly() {
        return currentAction == null || currentAction.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "plant_seeds";
    }

    private boolean beginPlant(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.emptyPlots().isEmpty()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        BlockPos civPos = civ.blockPosition();
        FarmWorkScanner.EmptyPlotTarget best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (FarmWorkScanner.EmptyPlotTarget plot : snap.emptyPlots()) {
            if (plot.targetSeed() == null || FarmWorkScanner.countCarried(civ, plot.targetSeed()) <= 0) {
                continue;
            }
            double d2 = plot.cropPos().distSqr(civPos);
            if (best == null || d2 < bestDistSq) {
                best = plot;
                bestDistSq = d2;
            }
        }
        if (best == null) {
            return false;
        }
        currentTarget = best;
        double reach = FarmWorkScanner.workReach(civ);
        if (!best.cropPos().closerToCenterThan(civ.position(), reach)) {
            currentAction = new MoveToAction(best.cropPos(), reach, null, null);
        } else {
            currentAction = createPlantAction(best.cropPos(), best.targetSeed());
        }
        currentAction.start(ctx);
        return true;
    }

    private static int countPlantablePlots(CivilianEntity civ, FarmWorkScanner.FarmSnapshot snap) {
        int count = 0;
        for (FarmWorkScanner.EmptyPlotTarget plot : snap.emptyPlots()) {
            if (plot.targetSeed() != null && FarmWorkScanner.countCarried(civ, plot.targetSeed()) > 0) {
                count++;
            }
        }
        return count;
    }

    private static PlaceBlockAction createPlantAction(BlockPos pos, Item seed) {
        return new PlaceBlockAction(
                pos,
                stack -> stack.is(seed),
                stack -> {
                    CropBlock crop = FarmCrops.cropBlockForSeed(stack.getItem());
                    return crop != null ? crop.defaultBlockState() : null;
                },
                FarmerUtility.PLANT_BASE_TICKS,
                FarmerUtility.PLANT_EXHAUSTION,
                true
        );
    }
}
