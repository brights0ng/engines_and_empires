package dev.brights0ng.enginesandempires.food.client;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.food.SpoilageConfig;
import dev.brights0ng.enginesandempires.food.icechest.IceChestInventory;
import dev.brights0ng.enginesandempires.food.icechest.IceChestMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The ice chest's screen: vanilla's dispenser background for the 3x3 of food, with the ice slot added on the left and a
 * cooling gauge beside it (hover it for how long the ice will last).
 */
public class IceChestScreen extends AbstractContainerScreen<IceChestMenu> {

    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("textures/gui/container/dispenser.png");

    private static final int GAUGE_X = 47;
    private static final int GAUGE_Y = 17;
    private static final int GAUGE_W = 5;
    private static final int GAUGE_H = 52;

    public IceChestScreen(IceChestMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (isHovering(GAUGE_X - 1, GAUGE_Y - 1, GAUGE_W + 2, GAUGE_H + 2, mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, gaugeTooltip(), mouseX, mouseY);
        } else {
            renderTooltip(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.blit(BACKGROUND, x, y, 0, 0, imageWidth, imageHeight);
        // The ice slot: one of the dispenser's own slot frames, copied to the left
        graphics.blit(BACKGROUND, x + IceChestMenu.ICE_X - 1, y + IceChestMenu.ICE_Y - 1, 61, 16, 18, 18);
        // The gauge: a frame, and a light blue fill that drains downwards as the ice burns
        graphics.fill(x + GAUGE_X - 1, y + GAUGE_Y - 1, x + GAUGE_X + GAUGE_W + 1, y + GAUGE_Y + GAUGE_H + 1, 0xFF373737);
        graphics.fill(x + GAUGE_X, y + GAUGE_Y, x + GAUGE_X + GAUGE_W, y + GAUGE_Y + GAUGE_H, 0xFF1E2A33);
        int max = menu.coolingMaxSeconds();
        int left = menu.coolingLeftSeconds();
        if (max > 0 && left > 0) {
            int filled = Math.max(1, Math.min(GAUGE_H, Math.round(GAUGE_H * (float) left / max)));
            graphics.fill(x + GAUGE_X, y + GAUGE_Y + GAUGE_H - filled, x + GAUGE_X + GAUGE_W, y + GAUGE_Y + GAUGE_H, 0xFF8FD8FF);
        }
    }

    private List<Component> gaugeTooltip() {
        List<Component> lines = new ArrayList<>();
        int left = menu.coolingLeftSeconds();
        lines.add(left > 0
                ? Component.translatable("gui.engines_and_empires.ice_chest.cooling", time(left))
                : Component.translatable("gui.engines_and_empires.ice_chest.not_cooling"));
        ItemStack ice = menu.getSlot(0).getItem();
        long perItem = IceChestInventory.coolingTicks(ice);
        if (perItem > 0) {
            lines.add(Component.translatable("gui.engines_and_empires.ice_chest.in_slot", time(perItem * ice.getCount() / 20))
                    .withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("gui.engines_and_empires.ice_chest.slowdown",
                Math.round(SpoilageConfig.iceChestSlowdown() * 100)).withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("gui.engines_and_empires.ice_chest.needs_food").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** Seconds as "h:mm:ss", or "m:ss" under an hour. */
    static String time(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
