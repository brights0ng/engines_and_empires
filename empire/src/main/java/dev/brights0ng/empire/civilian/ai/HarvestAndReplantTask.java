// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.region.FarmRegionScanner;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.CropBlock;

/**
 * Level-3 Farmer task: harvests the nearest mature {@link CropBlock} in a {@code FIELD} room (bare-handed, heavy
 * labor) and immediately replants that room's target seed (override or majority crop) if carried in pockets.
 */
public final class HarvestAndReplantTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    @Nullable
    private FarmWorkScanner.MatureCropTarget currentTarget;
    @Nullable
    private CivilianAction currentAction;

    public HarvestAndReplantTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_harvest_replant";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        return snap.valid()
                && !snap.matureCrops().isEmpty()
                && FarmWorkScanner.hasEmptyPocketSlot(ctx.civilian());
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_HARVEST_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        return FarmerUtility.harvestUrgency(scanner.scan(ctx).matureCrops().size());
    }

    @Override
    public void start(AiContext ctx) {
        currentTarget = null;
        currentAction = null;
        beginHarvest(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            return beginHarvest(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.FAILED;
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            currentAction.stop(ctx);
            CivilianAction finished = currentAction;
            currentAction = null;

            if (finished instanceof MoveToAction) {
                if (status != CivilianAction.Status.SUCCEEDED || currentTarget == null) {
                    return CivilianAction.Status.FAILED;
                }
                currentAction = createBreakAction(currentTarget.cropPos());
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            if (finished instanceof BreakBlockAction) {
                scanner.invalidate();
                if (status == CivilianAction.Status.SUCCEEDED && currentTarget != null) {
                    Item seed = currentTarget.replantSeed();
                    BlockPos pos = currentTarget.cropPos();
                    if (seed != null
                            && FarmWorkScanner.countCarried(ctx.civilian(), seed) > 0
                            && ctx.level().hasChunkAt(pos)
                            && ctx.level().getBlockState(pos).canBeReplaced()
                            && FarmRegionScanner.isFarmland(ctx.level().getBlockState(pos.below()))) {
                        currentAction = createPlantAction(pos, seed);
                        currentAction.start(ctx);
                        return CivilianAction.Status.RUNNING;
                    }
                }
                return status;
            }
            // PlaceBlockAction (replant) finished -> complete this single harvest+replant unit cleanly.
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
        return currentAction != null ? currentAction.name() : "harvest_replant";
    }

    private boolean beginHarvest(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.matureCrops().isEmpty()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        BlockPos civPos = civ.blockPosition();
        FarmWorkScanner.MatureCropTarget best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (FarmWorkScanner.MatureCropTarget t : snap.matureCrops()) {
            double d2 = t.cropPos().distSqr(civPos);
            if (best == null || d2 < bestDistSq) {
                best = t;
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
            currentAction = createBreakAction(best.cropPos());
        }
        currentAction.start(ctx);
        return true;
    }

    private static BreakBlockAction createBreakAction(BlockPos pos) {
        return new BreakBlockAction(
                pos,
                null,
                FarmerUtility.HARVEST_BASE_TICKS,
                FarmerUtility.HARVEST_EXHAUSTION,
                true,
                FarmCrops::isMatureCrop
        );
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
