// Written by Gemini.
package dev.brights0ng.empire.client;

import dev.brights0ng.empire.civilian.CivilianInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Vanilla chest-style screen for {@link CivilianInventoryMenu}: Main Hand and Offhand slots on the top row,
 * the 9 civilian pocket slots below them, and the standard player inventory underneath.
 */
public final class CivilianInventoryScreen extends AbstractContainerScreen<CivilianInventoryMenu> {

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final int TOP_HEADER_H = 17;
    private static final int POCKETS_ROW_Y = CivilianInventoryMenu.POCKETS_Y - 1;
    private static final int BOTTOM_PANEL_Y = POCKETS_ROW_Y + 18;
    private static final int BOTTOM_PANEL_H = 96;
    private static final int LABEL_COLOR = 0x404040;

    private static final Component MAIN_HAND_LABEL =
            Component.translatable("gui.empire.civilian_inventory.main_hand");
    private static final Component OFF_HAND_LABEL =
            Component.translatable("gui.empire.civilian_inventory.offhand");
    private static final Component MAIN_HAND_TOOLTIP =
            Component.translatable("gui.empire.civilian_inventory.main_hand_tooltip");
    private static final Component OFF_HAND_TOOLTIP =
            Component.translatable("gui.empire.civilian_inventory.offhand_tooltip");

    public CivilianInventoryScreen(CivilianInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = BOTTOM_PANEL_Y + BOTTOM_PANEL_H;
        this.inventoryLabelY = BOTTOM_PANEL_Y + 3;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Slot hovered = getSlotUnderMouse();
        if (menu.getCarried().isEmpty() && hovered != null && !hovered.hasItem()) {
            if (hovered.index == CivilianInventoryMenu.SLOT_MAIN_HAND) {
                graphics.renderTooltip(font, MAIN_HAND_TOOLTIP, mouseX, mouseY);
                return;
            }
            if (hovered.index == CivilianInventoryMenu.SLOT_OFF_HAND) {
                graphics.renderTooltip(font, OFF_HAND_TOOLTIP, mouseX, mouseY);
                return;
            }
        }
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        // Top border + title area
        graphics.blit(BACKGROUND, x, y, 0, 0, imageWidth, TOP_HEADER_H);
        // Blank panel + side borders behind the hands row and the 6px gap above pockets (24px = 14 + 10)
        graphics.blit(BACKGROUND, x, y + TOP_HEADER_H, 0, 126, imageWidth, 14);
        graphics.blit(BACKGROUND, x, y + TOP_HEADER_H + 14, 0, 126, imageWidth, 10);
        // Individual slot frames for Main Hand and Offhand
        graphics.blit(BACKGROUND, x + CivilianInventoryMenu.MAIN_HAND_X - 1, y + CivilianInventoryMenu.HANDS_Y - 1,
                7, 17, 18, 18);
        graphics.blit(BACKGROUND, x + CivilianInventoryMenu.OFF_HAND_X - 1, y + CivilianInventoryMenu.HANDS_Y - 1,
                7, 17, 18, 18);
        // Full 9-slot row for civilian pockets
        graphics.blit(BACKGROUND, x, y + POCKETS_ROW_Y, 0, 17, imageWidth, 18);
        // Player inventory + hotbar + bottom border
        graphics.blit(BACKGROUND, x, y + BOTTOM_PANEL_Y, 0, 126, imageWidth, BOTTOM_PANEL_H);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        graphics.drawString(font, MAIN_HAND_LABEL,
                CivilianInventoryMenu.MAIN_HAND_X + 21, CivilianInventoryMenu.HANDS_Y + 5, LABEL_COLOR, false);
        graphics.drawString(font, OFF_HAND_LABEL,
                CivilianInventoryMenu.OFF_HAND_X + 21, CivilianInventoryMenu.HANDS_Y + 5, LABEL_COLOR, false);
    }
}
