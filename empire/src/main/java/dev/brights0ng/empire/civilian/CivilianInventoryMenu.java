// Written by Gemini.
package dev.brights0ng.empire.civilian;

import javax.annotation.Nullable;

import com.mojang.datafixers.util.Pair;

import dev.brights0ng.empire.EmpireContent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Debug chest-style container menu exposing strictly a {@link CivilianEntity}'s 2 hand slots (main hand, offhand)
 * and 9 pocket slots above the player's inventory.
 */
public final class CivilianInventoryMenu extends AbstractContainerMenu {

    public static final int SLOT_MAIN_HAND = CivilianInventoryContainer.SLOT_MAIN_HAND;
    public static final int SLOT_OFF_HAND = CivilianInventoryContainer.SLOT_OFF_HAND;
    public static final int FIRST_POCKET = CivilianInventoryContainer.FIRST_POCKET;
    public static final int CIVILIAN_SLOT_COUNT = CivilianInventoryContainer.SIZE;
    public static final int PLAYER_INV_START = CIVILIAN_SLOT_COUNT;
    public static final int PLAYER_INV_END = PLAYER_INV_START + 36;

    public static final int MAIN_HAND_X = 8;
    public static final int OFF_HAND_X = 62;
    public static final int HANDS_Y = 18;
    public static final int POCKETS_X = 8;
    public static final int POCKETS_Y = 42;
    public static final int PLAYER_INV_Y = 73;
    public static final int PLAYER_HOTBAR_Y = 131;

    private static final ResourceLocation EMPTY_MAIN_HAND_ICON =
            ResourceLocation.withDefaultNamespace("item/empty_slot_sword");

    private final Container container;
    @Nullable
    private final CivilianEntity civilian;

    /** Client-side constructor created when the server opens the menu. */
    public CivilianInventoryMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(CIVILIAN_SLOT_COUNT), null);
    }

    /** Server-side constructor backed live by {@code civilian}'s hands and 9 pocket slots. */
    public CivilianInventoryMenu(int containerId, Inventory playerInventory, CivilianEntity civilian) {
        this(containerId, playerInventory, new CivilianInventoryContainer(civilian), civilian);
    }

    private CivilianInventoryMenu(int containerId, Inventory playerInventory, Container container,
                                  @Nullable CivilianEntity civilian) {
        super(EmpireContent.CIVILIAN_INVENTORY_MENU.get(), containerId);
        checkContainerSize(container, CIVILIAN_SLOT_COUNT);
        this.container = container;
        this.civilian = civilian;
        container.startOpen(playerInventory.player);

        // Slot 0: Main hand
        addSlot(new Slot(container, SLOT_MAIN_HAND, MAIN_HAND_X, HANDS_Y) {
            @Override
            public void setChanged() {
                if (civilian != null) {
                    civilian.cancelActiveUseForDirectHandEdit();
                }
                super.setChanged();
            }

            @Override
            public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                return Pair.of(InventoryMenu.BLOCK_ATLAS, EMPTY_MAIN_HAND_ICON);
            }
        });

        // Slot 1: Offhand
        addSlot(new Slot(container, SLOT_OFF_HAND, OFF_HAND_X, HANDS_Y) {
            @Override
            public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
            }
        });

        // Slots 2..10: 9 civilian pocket slots
        for (int col = 0; col < CivilianEntity.INVENTORY_SIZE; col++) {
            addSlot(new Slot(container, FIRST_POCKET + col, POCKETS_X + col * 18, POCKETS_Y));
        }

        // Player inventory (3 rows x 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + col, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }

        // Player hotbar (1 row x 9)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_HOTBAR_Y));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < CIVILIAN_SLOT_COUNT) {
            if (!moveItemStackTo(stack, PLAYER_INV_START, PLAYER_INV_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Fill the 9 pockets first, then fall back to main/offhand if pockets are full.
            boolean moved = moveItemStackTo(stack, FIRST_POCKET, CIVILIAN_SLOT_COUNT, false);
            if (!stack.isEmpty()) {
                moved |= moveItemStackTo(stack, SLOT_MAIN_HAND, FIRST_POCKET, false);
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
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
    public void removed(Player player) {
        super.removed(player);
        container.stopOpen(player);
        if (civilian != null) {
            civilian.syncNeedsToRecord();
        }
    }
}
