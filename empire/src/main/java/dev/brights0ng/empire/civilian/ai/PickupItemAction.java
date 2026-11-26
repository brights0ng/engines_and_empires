// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.Objects;

import dev.brights0ng.empire.civilian.CivilianEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Reusable Level-4 item-pickup primitive: walks to a nearby {@link ItemEntity} and collects it into pockets.
 */
public final class PickupItemAction implements CivilianAction {

    private static final double PICKUP_REACH = 2.0;
    private static final int TIMEOUT_TICKS = 160;
    private static final int REPATH_INTERVAL = 15;

    private final ItemEntity itemEntity;
    private int ticks;
    private int repathTicks;

    public PickupItemAction(ItemEntity itemEntity) {
        this.itemEntity = Objects.requireNonNull(itemEntity, "itemEntity");
    }

    @Override
    public String name() {
        return "pickup_item";
    }

    @Override
    public void start(AiContext ctx) {
        ticks = 0;
        repathTicks = 0;
        if (itemEntity.isAlive()) {
            ctx.civilian().getNavigation().moveTo(itemEntity, MoveToAction.DEFAULT_WALK_SPEED);
        }
    }

    @Override
    public Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        if (!itemEntity.isAlive() || itemEntity.getItem().isEmpty()) {
            civ.getNavigation().stop();
            return Status.FAILED;
        }
        ticks++;
        civ.schedule().addWorkDoneTicks(1);

        if (civ.distanceToSqr(itemEntity) <= PICKUP_REACH * PICKUP_REACH) {
            civ.getNavigation().stop();
            ItemStack stack = itemEntity.getItem();
            ItemStack remainder = civ.inventory().addItem(stack.copy());
            int picked = stack.getCount() - remainder.getCount();
            if (picked <= 0) {
                return Status.FAILED;
            }
            if (remainder.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(remainder);
            }
            ctx.level().playSound(null, civ.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL,
                    0.2F, ((ctx.level().random.nextFloat() - ctx.level().random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
            civ.syncNeedsToRecord();
            return Status.SUCCEEDED;
        }

        if (ticks >= TIMEOUT_TICKS) {
            civ.getNavigation().stop();
            return Status.FAILED;
        }
        repathTicks++;
        if (repathTicks >= REPATH_INTERVAL || civ.getNavigation().isDone()) {
            repathTicks = 0;
            civ.getNavigation().moveTo(itemEntity, MoveToAction.DEFAULT_WALK_SPEED);
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(AiContext ctx) {
        ctx.civilian().getNavigation().stop();
    }
}
