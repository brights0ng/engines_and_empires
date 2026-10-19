// Written by ChatGPT. Renders Simulated's original altitude-sensor GUI atlas in place; no assets are copied.
package dev.brights0ng.meteodyne.compat.simulated.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.brights0ng.meteodyne.instrument.SensorMenu;
import dev.brights0ng.meteodyne.instrument.client.SensorScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** Loaded only when Simulated is present. Its 42x206 scale, 10x200 light strip, and 26x16 handles use the original atlas. */
public final class SimulatedSensorScreen extends SensorScreen {
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("simulated", "textures/gui/altitude_sensor.png");

    public SimulatedSensorScreen(SensorMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }

    @Override
    protected void drawPanel(GuiGraphics graphics) {
        RenderSystem.enableBlend();
        graphics.blit(ATLAS, leftPos, topPos, 0, 0, 42, 206, 256, 256);
    }

    @Override
    protected void drawHandle(GuiGraphics graphics, boolean zero, int centerY) {
        graphics.blit(ATLAS, leftPos + (zero ? -6 : 22), centerY - 8, 64, 0, 26, 16, 256, 256);
    }

    @Override
    protected void drawOutputRow(GuiGraphics graphics, int row, int alpha) {
        RenderSystem.setShaderColor(1, 1, 1, alpha / 255f);
        graphics.blit(ATLAS, leftPos + 16, topPos + 3 + row, 48, row, 10, 1, 256, 256);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }
}
