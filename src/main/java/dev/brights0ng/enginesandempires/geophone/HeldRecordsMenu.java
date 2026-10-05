package dev.brights0ng.enginesandempires.geophone;

import java.util.function.Supplier;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * A board of readings kept on an item the player carries: the logbook, or the portable record display. The item is found
 * by its place in the player's inventory. That slot is locked while the screen is open, so the item cannot be moved out
 * from under it, and the screen closes if the item is no longer there.
 */
public abstract class HeldRecordsMenu extends ReadingBoardMenu {

    private final int itemIndex;
    private final Supplier<DataComponentType<Logbook>> component;
    private final Logbook empty;

    protected HeldRecordsMenu(MenuType<?> type, int containerId, Inventory inventory, int itemIndex,
                              Supplier<DataComponentType<Logbook>> component, Logbook empty,
                              int readerX, int readerY, int inventoryX, int inventoryY) {
        super(type, containerId, inventory, itemIndex, readerX, readerY, inventoryX, inventoryY);
        this.itemIndex = itemIndex;
        this.component = component;
        this.empty = empty;
    }

    /** Whether this is the kind of item the board lives on. */
    protected abstract boolean holds(ItemStack stack);

    /** Where the item is in the player's inventory. */
    public int itemIndex() {
        return itemIndex;
    }

    /** The item holding the readings. */
    public ItemStack itemStack() {
        return inventory.getItem(itemIndex);
    }

    @Override
    public Logbook records() {
        ItemStack stack = itemStack();
        return holds(stack) ? stack.getOrDefault(component.get(), empty) : empty;
    }

    @Override
    protected boolean hasStore() {
        return holds(itemStack());
    }

    @Override
    protected void setRecords(Logbook records) {
        itemStack().set(component.get(), records);
    }

    @Override
    public boolean stillValid(Player player) {
        return holds(itemStack());
    }
}
