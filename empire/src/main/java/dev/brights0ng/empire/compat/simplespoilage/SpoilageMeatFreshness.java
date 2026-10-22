// Written by Claude.
package dev.brights0ng.empire.compat.simplespoilage;

import dev.brights0ng.empire.sacrifice.MeatFreshness;
import dev.brights0ng.empire.sacrifice.MeatStage;
import dev.brights0ng.simplespoilage.Cohort;
import dev.brights0ng.simplespoilage.FoodFreshness;
import dev.brights0ng.simplespoilage.FoodStage;
import dev.brights0ng.simplespoilage.SpoilClock;
import dev.brights0ng.simplespoilage.SpoilTimes;
import dev.brights0ng.simplespoilage.Spoilage;
import net.minecraft.world.item.ItemStack;

/**
 * Meat freshness from Simple Spoilage. A sacrifice shrinks the held stack, and a stack that shrinks with nothing else saying
 * which units left loses its freshest ones ({@code FoodFreshness.removeFreshest}), so the units judged are the freshest
 * {@code units} of the stack: its cohorts are kept freshest first.
 */
final class SpoilageMeatFreshness implements MeatFreshness {

    @Override
    public MeatStage freshest(ItemStack stack, int units) {
        return range(stack, units)[0];
    }

    @Override
    public MeatStage stalest(ItemStack stack, int units) {
        return range(stack, units)[1];
    }

    private static MeatStage[] range(ItemStack stack, int units) {
        MeatStage[] fresh = {MeatStage.FRESH, MeatStage.FRESH};
        if (!Spoilage.isSpoilable(stack)) {
            return fresh;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return fresh;
        }
        FoodFreshness view = Spoilage.view(stack, now);
        SpoilTimes times = Spoilage.times();
        MeatStage freshest = null;
        MeatStage stalest = null;
        int need = units;
        for (Cohort cohort : view.cohorts()) {
            if (need <= 0) {
                break;
            }
            int take = Math.min(need, cohort.count());
            if (take <= 0) {
                continue;
            }
            MeatStage stage = of(times.stage(now - cohort.born()));
            freshest = freshest == null || stage.ordinal() < freshest.ordinal() ? stage : freshest;
            stalest = stalest == null || stage.ordinal() > stalest.ordinal() ? stage : stalest;
            need -= take;
        }
        return freshest == null ? fresh : new MeatStage[]{freshest, stalest};
    }

    private static MeatStage of(FoodStage stage) {
        return switch (stage) {
            case FRESH -> MeatStage.FRESH;
            case RIPE -> MeatStage.RIPE;
            case STALE -> MeatStage.STALE;
            case ROTTING -> MeatStage.ROTTING;
        };
    }
}
