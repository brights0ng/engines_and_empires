// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Level-3 Farmer task: unloads harvested produce and surplus seeds (beyond the per-crop missing-plot reserve, capped
 * at 1 stack per seed type) into marked {@code SHED} storage blocks. Never dumps personal meals or hoes.
 */
public final class DumpToStorageTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    @Nullable
    private BlockPos targetStorage;
    @Nullable
    private CivilianAction currentAction;

    public DumpToStorageTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_dump_storage";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid()) {
            return false;
        }
        Map<Item, Integer> dumpable = FarmWorkScanner.computeDumpableCounts(ctx.civilian(), snap, false);
        return FarmWorkScanner.findDepositStorage(ctx.level(), ctx.civilian(), snap, dumpable) != null;
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_DUMP_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        return FarmerUtility.dumpUrgency(FarmWorkScanner.pocketFillFraction(ctx.civilian()), snap.hasFieldWork());
    }

    @Override
    public void start(AiContext ctx) {
        targetStorage = null;
        currentAction = null;
        beginDeposit(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            return beginDeposit(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.FAILED;
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
                FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
                Map<Item, Integer> dumpable = FarmWorkScanner.computeDumpableCounts(ctx.civilian(), snap, false);
                if (dumpable.isEmpty()) {
                    return CivilianAction.Status.SUCCEEDED;
                }
                currentAction = createDepositAction(targetStorage, dumpable);
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
        targetStorage = null;
    }

    @Override
    public boolean canYieldCleanly() {
        return currentAction == null || currentAction.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "dump_storage";
    }

    private boolean beginDeposit(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        Map<Item, Integer> dumpable = FarmWorkScanner.computeDumpableCounts(civ, snap, false);
        BlockPos storage = FarmWorkScanner.findDepositStorage(ctx.level(), civ, snap, dumpable);
        if (storage == null) {
            return false;
        }
        targetStorage = storage;
        double reach = FarmWorkScanner.workReach(civ);
        if (!storage.closerToCenterThan(civ.position(), reach)) {
            BlockPos approach = FarmWorkScanner.approachPos(ctx.level(), ctx.empire(), storage, civ.blockPosition());
            currentAction = new MoveToAction(approach, Math.min(2.5, reach), null, null);
        } else {
            currentAction = createDepositAction(storage, dumpable);
        }
        currentAction.start(ctx);
        return true;
    }

    private static TransferItemsAction createDepositAction(BlockPos storage, Map<Item, Integer> dumpable) {
        Map<Item, Integer> remaining = new HashMap<>(dumpable);
        return new TransferItemsAction(
                storage,
                TransferItemsAction.Mode.DEPOSIT,
                stack -> {
                    int want = remaining.getOrDefault(stack.getItem(), 0);
                    if (want <= 0) {
                        return 0;
                    }
                    int take = Math.min(want, stack.getCount());
                    remaining.put(stack.getItem(), want - take);
                    return take;
                },
                FarmerUtility.TRANSFER_BASE_TICKS
        );
    }
}
