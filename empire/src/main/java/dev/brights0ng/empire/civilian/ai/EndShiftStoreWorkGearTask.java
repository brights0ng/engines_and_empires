// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.Map;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Level-3 end-of-shift Farmer task: stows all farm work gear (hoes, seeds, and raw crop produce) into shed storage
 * before leaving the {@code WORK} slot (personal prepared meals stay in pockets).
 */
public final class EndShiftStoreWorkGearTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    @Nullable
    private BlockPos targetStorage;
    @Nullable
    private CivilianAction currentAction;

    public EndShiftStoreWorkGearTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_end_shift_store";
    }

    @Override
    public boolean isEndShiftTask() {
        return true;
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid()) {
            return false;
        }
        Map<Item, Integer> workItems = FarmWorkScanner.computeDumpableCounts(ctx.civilian(), snap, true);
        return FarmWorkScanner.findDepositStorage(ctx.level(), ctx.civilian(), snap, workItems) != null;
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_END_SHIFT_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        targetStorage = null;
        currentAction = null;
        beginNextDeposit(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            if (!beginNextDeposit(ctx)) {
                return CivilianAction.Status.SUCCEEDED;
            }
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            currentAction.stop(ctx);
            boolean wasMove = currentAction instanceof MoveToAction;
            currentAction = null;
            if (wasMove) {
                if (status != CivilianAction.Status.SUCCEEDED || targetStorage == null) {
                    return CivilianAction.Status.FAILED;
                }
                currentAction = createDepositAction(targetStorage);
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            scanner.invalidate();
            // If more work gear remains (e.g. first chest filled up), deposit into another shed chest.
            if (status == CivilianAction.Status.SUCCEEDED && beginNextDeposit(ctx)) {
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
        targetStorage = null;
        EquipAction.stowMainHand(ctx.civilian());
    }

    @Override
    public boolean canYieldCleanly() {
        return currentAction == null || currentAction.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "end_shift_store";
    }

    private boolean beginNextDeposit(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        Map<Item, Integer> workItems = FarmWorkScanner.computeDumpableCounts(civ, snap, true);
        BlockPos storage = FarmWorkScanner.findDepositStorage(ctx.level(), civ, snap, workItems);
        if (storage == null) {
            return false;
        }
        targetStorage = storage;
        double reach = FarmWorkScanner.workReach(civ);
        if (!storage.closerToCenterThan(civ.position(), reach)) {
            BlockPos approach = FarmWorkScanner.approachPos(ctx.level(), ctx.empire(), storage, civ.blockPosition());
            currentAction = new MoveToAction(approach, Math.min(2.5, reach), null, null);
        } else {
            currentAction = createDepositAction(storage);
        }
        currentAction.start(ctx);
        return true;
    }

    private static TransferItemsAction createDepositAction(BlockPos storage) {
        return new TransferItemsAction(
                storage,
                TransferItemsAction.Mode.DEPOSIT,
                stack -> FarmCrops.isFarmWorkItem(stack) ? stack.getCount() : 0,
                FarmerUtility.TRANSFER_BASE_TICKS
        );
    }
}
