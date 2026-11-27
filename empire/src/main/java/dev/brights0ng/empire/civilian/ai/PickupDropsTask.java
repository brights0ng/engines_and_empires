// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Level-3 Farmer task: sweeps up stray crop/seed {@link ItemEntity} drops sitting inside or near {@code FIELD} rooms.
 */
public final class PickupDropsTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    @Nullable
    private PickupItemAction currentAction;

    public PickupDropsTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_pickup_drops";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.strayDrops().isEmpty()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        for (ItemEntity item : snap.strayDrops()) {
            if (item.isAlive() && FarmWorkScanner.canFitAny(civ, item.getItem())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_PICKUP_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        return FarmerUtility.pickupUrgency(scanner.scan(ctx).strayDrops().size());
    }

    @Override
    public MomentumPolicy momentumPolicy() {
        return MomentumPolicy.PRESERVE;
    }

    @Override
    public void start(AiContext ctx) {
        currentAction = null;
        beginPickup(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            return beginPickup(ctx) ? CivilianAction.Status.RUNNING : CivilianAction.Status.SUCCEEDED;
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            currentAction.stop(ctx);
            currentAction = null;
            scanner.invalidate();
            if (status == CivilianAction.Status.SUCCEEDED && beginPickup(ctx)) {
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
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "pickup_drops";
    }

    private boolean beginPickup(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.strayDrops().isEmpty()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        ItemEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (ItemEntity item : snap.strayDrops()) {
            if (!item.isAlive() || !FarmWorkScanner.canFitAny(civ, item.getItem())) {
                continue;
            }
            double d2 = civ.distanceToSqr(item);
            if (best == null || d2 < bestDistSq) {
                best = item;
                bestDistSq = d2;
            }
        }
        if (best == null) {
            return false;
        }
        currentAction = new PickupItemAction(best);
        currentAction.start(ctx);
        return true;
    }
}
