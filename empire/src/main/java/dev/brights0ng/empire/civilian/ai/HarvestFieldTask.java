// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.CivilianSkill;
import dev.brights0ng.empire.rules.CivilianSkills;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;

/**
 * Level-3 Farmer task (harvest-only): selects the nearest {@code FIELD} room with mature {@link net.minecraft.world.level.block.CropBlock}s
 * and harvests every mature crop in that field before returning to Level 2 for re-evaluation.
 *
 * <p>Interrupts immediately (preserving Harvest momentum) if a harvested drop overflows onto the ground because pockets
 * have no available space.
 */
public final class HarvestFieldTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    private final Set<BlockPos> skippedPositions = new HashSet<>();
    private int activeRoomIndex = -1;
    @Nullable
    private BlockPos currentTargetPos;
    @Nullable
    private CivilianAction currentAction;

    public HarvestFieldTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_harvest_field";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.matureCrops().isEmpty()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        return !scanner.isInventoryOverflowed(civ)
                && !FarmWorkScanner.hasUnpickableStrayDrops(civ, snap);
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
    public MomentumPolicy momentumPolicy() {
        return MomentumPolicy.SET;
    }

    @Override
    public double momentumBonus() {
        return FarmerUtility.FIELD_WORK_MOMENTUM_BONUS;
    }

    @Override
    public void start(AiContext ctx) {
        scanner.clearSeedFetchRequest();
        skippedPositions.clear();
        activeRoomIndex = -1;
        currentTargetPos = null;
        currentAction = null;

        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.matureCrops().isEmpty()) {
            return;
        }
        activeRoomIndex = pickNearestRoom(ctx.civilian().blockPosition(), snap);
        if (activeRoomIndex >= 0) {
            beginNextCropInRoom(ctx);
        }
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (activeRoomIndex < 0) {
            return CivilianAction.Status.FAILED;
        }
        if (currentAction == null) {
            return beginNextCropInRoom(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.SUCCEEDED;
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status == CivilianAction.Status.RUNNING) {
            return CivilianAction.Status.RUNNING;
        }

        currentAction.stop(ctx);
        CivilianAction finished = currentAction;
        currentAction = null;

        if (finished instanceof MoveToAction) {
            if (status == CivilianAction.Status.SUCCEEDED && currentTargetPos != null) {
                currentAction = createBreakAction(currentTargetPos);
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            if (currentTargetPos != null) {
                skippedPositions.add(currentTargetPos);
            }
            return beginNextCropInRoom(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.SUCCEEDED;
        }

        scanner.invalidate();
        if (finished instanceof BreakBlockAction breakAction && breakAction.droppedOverflow()) {
            scanner.flagInventoryOverflow();
            return CivilianAction.Status.FAILED;
        }
        if (status != CivilianAction.Status.SUCCEEDED && currentTargetPos != null) {
            skippedPositions.add(currentTargetPos);
        }

        // Check for unpickable ground drops before moving to the next crop in this field.
        FarmWorkScanner.FarmSnapshot after = scanner.scan(ctx);
        if (!after.valid()) {
            return CivilianAction.Status.FAILED;
        }
        if (FarmWorkScanner.hasUnpickableStrayDrops(ctx.civilian(), after)) {
            scanner.flagInventoryOverflow();
            return CivilianAction.Status.FAILED;
        }

        // Continue harvesting this same field without re-evaluating Level 2 until the field is finished.
        return beginNextCropInRoom(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.SUCCEEDED;
    }

    @Override
    public void stop(AiContext ctx) {
        if (currentAction != null) {
            currentAction.stop(ctx);
            currentAction = null;
        }
        currentTargetPos = null;
        activeRoomIndex = -1;
        skippedPositions.clear();
        EquipAction.stowMainHand(ctx.civilian());
    }

    @Override
    public boolean canYieldCleanly() {
        return currentAction == null || currentAction.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "harvest_field";
    }

    private static int pickNearestRoom(BlockPos civPos, FarmWorkScanner.FarmSnapshot snap) {
        int bestRoom = -1;
        double bestDistSq = Double.MAX_VALUE;
        for (FarmWorkScanner.MatureCropTarget t : snap.matureCrops()) {
            double d2 = t.cropPos().distSqr(civPos);
            if (bestRoom < 0 || d2 < bestDistSq) {
                bestRoom = t.roomIndex();
                bestDistSq = d2;
            }
        }
        return bestRoom;
    }

    private boolean beginNextCropInRoom(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.matureCrops().isEmpty()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        BlockPos civPos = civ.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (FarmWorkScanner.MatureCropTarget t : snap.matureCrops()) {
            if (t.roomIndex() != activeRoomIndex || skippedPositions.contains(t.cropPos())) {
                continue;
            }
            double d2 = t.cropPos().distSqr(civPos);
            if (best == null || d2 < bestDistSq) {
                best = t.cropPos();
                bestDistSq = d2;
            }
        }
        if (best == null) {
            currentTargetPos = null;
            return false;
        }
        currentTargetPos = best;
        double reach = FarmWorkScanner.workReach(civ);
        if (!best.closerToCenterThan(civ.position(), reach)) {
            currentAction = new MoveToAction(best, reach, null, null);
        } else {
            currentAction = createBreakAction(best);
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
                FarmCrops::isMatureCrop,
                CivilianSkill.HARVESTING,
                CivilianSkills.HARVEST_CROP_BASE_XP,
                true
        );
    }
}
