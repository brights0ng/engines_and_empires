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
 * Level-3 Farmer task: fetches a hoe (when reverted soil needs tilling) and/or missing crop seeds (up to 1 stack per
 * needed crop type) from marked {@code SHED} storage blocks.
 */
public final class FetchSuppliesTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    @Nullable
    private BlockPos targetStorage;
    @Nullable
    private CivilianAction currentAction;

    public FetchSuppliesTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_fetch_supplies";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        boolean needHoe = !snap.revertedDirt().isEmpty() && !FarmWorkScanner.hasHoe(civ);
        Map<Item, Integer> neededSeeds = FarmWorkScanner.computeMissingSeedsToFetch(civ, snap);
        return FarmWorkScanner.findWithdrawStorage(ctx.level(), civ, snap, needHoe, neededSeeds) != null;
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_FETCH_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        CivilianEntity civ = ctx.civilian();
        boolean needHoe = !snap.revertedDirt().isEmpty() && !FarmWorkScanner.hasHoe(civ);
        int missingSeeds = 0;
        for (int n : FarmWorkScanner.computeMissingSeedsToFetch(civ, snap).values()) {
            missingSeeds += n;
        }
        return FarmerUtility.fetchUrgency(needHoe, missingSeeds);
    }

    @Override
    public void start(AiContext ctx) {
        targetStorage = null;
        currentAction = null;
        beginFetch(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            return beginFetch(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.FAILED;
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
                CivilianEntity civ = ctx.civilian();
                boolean needHoe = !snap.revertedDirt().isEmpty() && !FarmWorkScanner.hasHoe(civ);
                Map<Item, Integer> neededSeeds = FarmWorkScanner.computeMissingSeedsToFetch(civ, snap);
                if (!needHoe && neededSeeds.isEmpty()) {
                    return CivilianAction.Status.SUCCEEDED;
                }
                currentAction = createWithdrawAction(targetStorage, needHoe, neededSeeds);
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
        return currentAction != null ? currentAction.name() : "fetch_supplies";
    }

    private boolean beginFetch(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        boolean needHoe = !snap.revertedDirt().isEmpty() && !FarmWorkScanner.hasHoe(civ);
        Map<Item, Integer> neededSeeds = FarmWorkScanner.computeMissingSeedsToFetch(civ, snap);
        BlockPos storage = FarmWorkScanner.findWithdrawStorage(ctx.level(), civ, snap, needHoe, neededSeeds);
        if (storage == null) {
            return false;
        }
        targetStorage = storage;
        double reach = FarmWorkScanner.workReach(civ);
        if (!storage.closerToCenterThan(civ.position(), reach)) {
            BlockPos approach = FarmWorkScanner.approachPos(ctx.level(), ctx.empire(), storage, civ.blockPosition());
            currentAction = new MoveToAction(approach, Math.min(2.5, reach), null, null);
        } else {
            currentAction = createWithdrawAction(storage, needHoe, neededSeeds);
        }
        currentAction.start(ctx);
        return true;
    }

    private static TransferItemsAction createWithdrawAction(BlockPos storage, boolean needHoe,
                                                            Map<Item, Integer> neededSeeds) {
        boolean[] stillNeedsHoe = {needHoe};
        Map<Item, Integer> remainingSeeds = new HashMap<>(neededSeeds);
        return new TransferItemsAction(
                storage,
                TransferItemsAction.Mode.WITHDRAW,
                stack -> {
                    if (stillNeedsHoe[0] && FarmCrops.isHoe(stack)) {
                        stillNeedsHoe[0] = false;
                        return 1;
                    }
                    int want = remainingSeeds.getOrDefault(stack.getItem(), 0);
                    if (want > 0) {
                        int take = Math.min(want, stack.getCount());
                        remainingSeeds.put(stack.getItem(), want - take);
                        return take;
                    }
                    return 0;
                },
                FarmerUtility.TRANSFER_BASE_TICKS
        );
    }
}
