// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;

/**
 * Level-3 Farmer interruption/maintenance task: selects the nearest {@code FIELD} room with reverted/trampled soil
 * and re-tills all reverted tiles in that room using a hoe, preserving primary Harvest/Seed momentum.
 */
public final class RetillSoilTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    private final Set<BlockPos> skippedPositions = new HashSet<>();
    private int activeRoomIndex = -1;
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
    public MomentumPolicy momentumPolicy() {
        return MomentumPolicy.PRESERVE;
    }

    @Override
    public void start(AiContext ctx) {
        skippedPositions.clear();
        activeRoomIndex = -1;
        targetSoil = null;
        currentAction = null;

        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.revertedDirt().isEmpty() || !FarmWorkScanner.hasHoe(ctx.civilian())) {
            return;
        }
        activeRoomIndex = pickNearestRoom(ctx.civilian().blockPosition(), snap);
        if (activeRoomIndex >= 0) {
            beginNextSoilInRoom(ctx);
        }
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (activeRoomIndex < 0) {
            return CivilianAction.Status.FAILED;
        }
        if (currentAction == null) {
            return beginNextSoilInRoom(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.SUCCEEDED;
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status == CivilianAction.Status.RUNNING) {
            return CivilianAction.Status.RUNNING;
        }

        currentAction.stop(ctx);
        boolean wasMove = currentAction instanceof MoveToAction;
        currentAction = null;
        if (wasMove) {
            if (status == CivilianAction.Status.SUCCEEDED && targetSoil != null) {
                if (!FarmWorkScanner.hasHoe(ctx.civilian())) {
                    return CivilianAction.Status.FAILED;
                }
                currentAction = InteractBlockAction.till(targetSoil);
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            if (targetSoil != null) {
                skippedPositions.add(targetSoil);
            }
            return beginNextSoilInRoom(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.SUCCEEDED;
        }

        scanner.invalidate();
        if (status != CivilianAction.Status.SUCCEEDED && targetSoil != null) {
            skippedPositions.add(targetSoil);
        }
        if (!FarmWorkScanner.hasHoe(ctx.civilian())) {
            return CivilianAction.Status.FAILED;
        }
        return beginNextSoilInRoom(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.SUCCEEDED;
    }

    @Override
    public void stop(AiContext ctx) {
        if (currentAction != null) {
            currentAction.stop(ctx);
            currentAction = null;
        }
        targetSoil = null;
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
        return currentAction != null ? currentAction.name() : "retill_soil";
    }

    private static int pickNearestRoom(BlockPos civPos, FarmWorkScanner.FarmSnapshot snap) {
        int bestRoom = -1;
        double bestDistSq = Double.MAX_VALUE;
        for (FarmWorkScanner.RevertedSoilTarget t : snap.revertedDirt()) {
            double d2 = t.soilPos().distSqr(civPos);
            if (bestRoom < 0 || d2 < bestDistSq) {
                bestRoom = t.roomIndex();
                bestDistSq = d2;
            }
        }
        return bestRoom;
    }

    private boolean beginNextSoilInRoom(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.revertedDirt().isEmpty() || !FarmWorkScanner.hasHoe(ctx.civilian())) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        BlockPos civPos = civ.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (FarmWorkScanner.RevertedSoilTarget t : snap.revertedDirt()) {
            if (t.roomIndex() != activeRoomIndex || skippedPositions.contains(t.soilPos())) {
                continue;
            }
            double d2 = t.soilPos().distSqr(civPos);
            if (best == null || d2 < bestDistSq) {
                best = t.soilPos();
                bestDistSq = d2;
            }
        }
        if (best == null) {
            targetSoil = null;
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
