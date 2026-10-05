package dev.brights0ng.enginesandempires.food.icechest;

import javax.annotation.Nullable;

import dev.brights0ng.enginesandempires.food.FoodContent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * The ice chest's menu: the ice slot on the left with its gauge, the 3x3 of food in the middle (laid out like a dispenser),
 * and the player's inventory below.
 */
public class IceChestMenu extends AbstractContainerMenu {

    public static final int ICE_X = 26;
    public static final int ICE_Y = 35;
    private static final int PLAYER_START = IceChestInventory.SIZE;
    private static final int PLAYER_END = PLAYER_START + 36;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    @Nullable
    private final IceChestBlockEntity chest;

    /** The client's copy, made when the server opens the menu. */
    public IceChestMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new IceChestInventory(null), new SimpleContainerData(2), ContainerLevelAccess.NULL, null);
    }

    public IceChestMenu(int id, Inventory playerInventory, IceChestBlockEntity chest) {
        this(id, playerInventory, chest.inventory(), chest.data,
                ContainerLevelAccess.create(chest.getLevel(), chest.getBlockPos()), chest);
        chest.settle();
        chest.startOpen();
    }

    private IceChestMenu(int id, Inventory playerInventory, IceChestInventory inventory, ContainerData data,
                         ContainerLevelAccess access, @Nullable IceChestBlockEntity chest) {
        super(FoodContent.ICE_CHEST_MENU.get(), id);
        this.data = data;
        this.access = access;
        this.chest = chest;
        addSlot(new SlotItemHandler(inventory, IceChestInventory.ICE, ICE_X, ICE_Y));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new SlotItemHandler(inventory, IceChestInventory.FIRST_FOOD + row * 3 + col, 62 + col * 18, 17 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + col, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
        addDataSlots(data);
    }

    /** Seconds left of the ice now burning. */
    public int coolingLeftSeconds() {
        return data.get(0);
    }

    /** How long the ice now burning lasts in all, in seconds (0 when nothing is burning). */
    public int coolingMaxSeconds() {
        return data.get(1);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < PLAYER_START) {
            if (!moveItemStackTo(stack, PLAYER_START, PLAYER_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (IceChestInventory.coolingTicks(stack) > 0) {
            if (!moveItemStackTo(stack, IceChestInventory.ICE, IceChestInventory.ICE + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (IceChestInventory.isFood(stack)) {
            if (!moveItemStackTo(stack, IceChestInventory.FIRST_FOOD, IceChestInventory.SIZE, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, FoodContent.ICE_CHEST.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (chest != null) {
            chest.stopOpen();
        }
    }
}
