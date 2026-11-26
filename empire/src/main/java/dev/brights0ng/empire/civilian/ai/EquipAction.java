// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.Objects;
import java.util.function.Predicate;

import dev.brights0ng.empire.civilian.CivilianEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Reusable Level-4 equip primitive: moves a matching stack from the civilian's pockets into {@code MAIN_HAND}
 * (stowing any previous non-meal hand item back into pockets) and provides {@link #stowMainHand(CivilianEntity)}.
 */
public final class EquipAction implements CivilianAction {

    private final Predicate<ItemStack> filter;
    private final String label;

    public EquipAction(Predicate<ItemStack> filter, String label) {
        this.filter = Objects.requireNonNull(filter, "filter");
        this.label = label != null ? label : "item";
    }

    @Override
    public String name() {
        return "equip(" + label + ")";
    }

    @Override
    public Status tick(AiContext ctx) {
        return equipFromPockets(ctx.civilian(), filter) ? Status.SUCCEEDED : Status.FAILED;
    }

    /**
     * Ensures {@code civ}'s main hand holds a stack matching {@code filter}, swapping from pockets if needed.
     * Returns {@code false} if no matching stack is carried or the civilian is actively using an item (e.g. eating).
     */
    public static boolean equipFromPockets(CivilianEntity civ, Predicate<ItemStack> filter) {
        if (civ.isUsingItem()) {
            return false;
        }
        ItemStack main = civ.getMainHandItem();
        if (!main.isEmpty() && filter.test(main)) {
            return true;
        }
        SimpleContainer inv = civ.inventory();
        int matchSlot = -1;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack candidate = inv.getItem(slot);
            if (!candidate.isEmpty() && filter.test(candidate)) {
                matchSlot = slot;
                break;
            }
        }
        if (matchSlot < 0) {
            return false;
        }
        ItemStack picked = inv.removeItemNoUpdate(matchSlot);
        if (!main.isEmpty()) {
            // Put old hand item directly into the vacated pocket slot (or merge/store).
            inv.setItem(matchSlot, main.copy());
        }
        civ.setItemInHand(InteractionHand.MAIN_HAND, picked);
        civ.syncNeedsToRecord();
        return true;
    }

    /**
     * Moves any non-empty {@code MAIN_HAND} stack back into pockets (merging with existing stacks, dropping only on
     * complete overflow). Safe no-op while the civilian is mid-meal ({@code isUsingItem()}).
     */
    public static void stowMainHand(CivilianEntity civ) {
        if (civ.isUsingItem()) {
            return;
        }
        ItemStack main = civ.getMainHandItem();
        if (main.isEmpty()) {
            return;
        }
        civ.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        civ.storeOrDrop(main);
        civ.syncNeedsToRecord();
    }
}
