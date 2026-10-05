package dev.brights0ng.enginesandempires.food.icechest;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** One side's view of the ice chest for automation: some of its slots, and whether items may go in and come out there. */
final class IceChestSides implements IItemHandler {

    static final int[] FOOD_SLOTS = {1, 2, 3, 4, 5, 6, 7, 8, 9};

    private final IceChestInventory inventory;
    private final int[] slots;
    private final boolean insert;
    private final boolean extract;

    IceChestSides(IceChestInventory inventory, int[] slots, boolean insert, boolean extract) {
        this.inventory = inventory;
        this.slots = slots;
        this.insert = insert;
        this.extract = extract;
    }

    @Override
    public int getSlots() {
        return slots.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return inventory.getStackInSlot(slots[slot]);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return insert ? inventory.insertItem(slots[slot], stack, simulate) : stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return extract ? inventory.extractItem(slots[slot], amount, simulate) : ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return inventory.getSlotLimit(slots[slot]);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return insert && inventory.isItemValid(slots[slot], stack);
    }
}
