// Written by Gemini.
package dev.brights0ng.empire.civilian;

import java.util.Objects;

import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Live read/write {@link Container} view over a {@link CivilianEntity}'s main hand, offhand, and 9 pocket slots.
 * Mutations apply directly to the live entity without pausing its AI and flush pocket changes to its master record.
 */
public final class CivilianInventoryContainer implements Container {

    public static final int SLOT_MAIN_HAND = 0;
    public static final int SLOT_OFF_HAND = 1;
    public static final int FIRST_POCKET = 2;
    public static final int SIZE = FIRST_POCKET + CivilianEntity.INVENTORY_SIZE;

    private final CivilianEntity civilian;

    public CivilianInventoryContainer(CivilianEntity civilian) {
        this.civilian = Objects.requireNonNull(civilian, "civilian");
    }

    public CivilianEntity civilian() {
        return civilian;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return civilian.getMainHandItem().isEmpty()
                && civilian.getOffhandItem().isEmpty()
                && civilian.inventory().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot == SLOT_MAIN_HAND) {
            return civilian.getItemInHand(InteractionHand.MAIN_HAND);
        }
        if (slot == SLOT_OFF_HAND) {
            return civilian.getItemInHand(InteractionHand.OFF_HAND);
        }
        int pocket = slot - FIRST_POCKET;
        if (pocket >= 0 && pocket < CivilianEntity.INVENTORY_SIZE) {
            return civilian.inventory().getItem(pocket);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        if (slot == SLOT_MAIN_HAND) {
            ItemStack current = civilian.getItemInHand(InteractionHand.MAIN_HAND);
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            civilian.cancelActiveUseForDirectHandEdit();
            ItemStack taken = current.split(amount);
            if (current.isEmpty()) {
                civilian.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            setChanged();
            return taken;
        }
        if (slot == SLOT_OFF_HAND) {
            ItemStack current = civilian.getItemInHand(InteractionHand.OFF_HAND);
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (civilian.isUsingItem() && civilian.getUsedItemHand() == InteractionHand.OFF_HAND) {
                civilian.stopUsingItem();
            }
            ItemStack taken = current.split(amount);
            if (current.isEmpty()) {
                civilian.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            }
            setChanged();
            return taken;
        }
        int pocket = slot - FIRST_POCKET;
        if (pocket >= 0 && pocket < CivilianEntity.INVENTORY_SIZE) {
            ItemStack taken = civilian.inventory().removeItem(pocket, amount);
            if (!taken.isEmpty()) {
                setChanged();
            }
            return taken;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot == SLOT_MAIN_HAND) {
            ItemStack current = civilian.getItemInHand(InteractionHand.MAIN_HAND);
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            civilian.cancelActiveUseForDirectHandEdit();
            civilian.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return current;
        }
        if (slot == SLOT_OFF_HAND) {
            ItemStack current = civilian.getItemInHand(InteractionHand.OFF_HAND);
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (civilian.isUsingItem() && civilian.getUsedItemHand() == InteractionHand.OFF_HAND) {
                civilian.stopUsingItem();
            }
            civilian.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            return current;
        }
        int pocket = slot - FIRST_POCKET;
        if (pocket >= 0 && pocket < CivilianEntity.INVENTORY_SIZE) {
            return civilian.inventory().removeItemNoUpdate(pocket);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!stack.isEmpty()) {
            stack.limitSize(getMaxStackSize(stack));
        }
        if (slot == SLOT_MAIN_HAND) {
            civilian.cancelActiveUseForDirectHandEdit();
            civilian.setItemInHand(InteractionHand.MAIN_HAND, stack);
            setChanged();
            return;
        }
        if (slot == SLOT_OFF_HAND) {
            if (civilian.isUsingItem() && civilian.getUsedItemHand() == InteractionHand.OFF_HAND) {
                civilian.stopUsingItem();
            }
            civilian.setItemInHand(InteractionHand.OFF_HAND, stack);
            setChanged();
            return;
        }
        int pocket = slot - FIRST_POCKET;
        if (pocket >= 0 && pocket < CivilianEntity.INVENTORY_SIZE) {
            civilian.inventory().setItem(pocket, stack);
            setChanged();
        }
    }

    @Override
    public void setChanged() {
        civilian.inventory().setChanged();
        civilian.syncNeedsToRecord();
    }

    @Override
    public boolean stillValid(Player player) {
        return civilian.isAlive()
                && !civilian.isRemoved()
                && civilian.level() == player.level()
                && player.canInteractWithEntity(civilian, Container.DEFAULT_DISTANCE_BUFFER);
    }

    @Override
    public void clearContent() {
        civilian.cancelActiveUseForDirectHandEdit();
        civilian.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        civilian.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        civilian.inventory().clearContent();
        setChanged();
    }
}
