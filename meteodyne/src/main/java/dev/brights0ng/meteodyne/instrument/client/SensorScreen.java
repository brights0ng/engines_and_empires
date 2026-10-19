// Written by ChatGPT. Interaction/layout reference: Simulated's AltitudeSensorScreen; original assets stay with that mod.
package dev.brights0ng.meteodyne.instrument.client;

import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import dev.brights0ng.meteodyne.instrument.InstrumentNetwork;
import dev.brights0ng.meteodyne.instrument.SensorMenu;
import dev.brights0ng.meteodyne.instrument.SensorRange;
import dev.brights0ng.meteodyne.instrument.SensorRangeControls;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** A two-handle vertical range editor: drag endpoints, Ctrl-drag the whole range, and save on close. */
public class SensorScreen extends AbstractContainerScreen<SensorMenu> {
    protected static final int BAR_HEIGHT = 200;
    private SensorRangeControls controls;
    private int initialZero, initialFull;
    private double visualZero, visualFull, previousZero, previousFull;
    private double drawnZero, drawnFull;
    private Boolean dragging;
    private boolean selectedZero = true;
    private boolean keyboardFocus;
    private boolean submitted;
    private int ticksOpen;
    private int soundStep = -1;

    public SensorScreen(SensorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 42;
        imageHeight = 206;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ticksOpen++;
        if (controls == null && SensorRangeControls.valid(menu.zeroTenths(), menu.fullTenths())) {
            initialZero = menu.zeroTenths();
            initialFull = menu.fullTenths();
            controls = new SensorRangeControls(initialZero, initialFull, menu.minimum(), menu.maximum());
            visualZero = previousZero = controls.position(initialZero);
            visualFull = previousFull = controls.position(initialFull);
        }
        if (controls != null) {
            previousZero = visualZero;
            previousFull = visualFull;
            visualZero += (controls.position(controls.zero()) - visualZero) * 0.85;
            visualFull += (controls.position(controls.full()) - visualFull) * 0.85;
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int alpha = (int) (0x50 * Math.min(1, (ticksOpen + partialTick) / 20.0));
        graphics.fillGradient(0, 0, width, height, alpha << 24 | 0x101010, alpha << 24 | 0x101010);
        renderBg(graphics, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics);
        if (controls == null) { return; }
        double zero = drawnZero = previousZero + (visualZero - previousZero) * partialTick;
        double full = drawnFull = previousFull + (visualFull - previousFull) * partialTick;
        if (Math.abs(full - zero) > 1e-6) {
            for (int row = 0; row < BAR_HEIGHT; row++) {
                double value = 1 - row / (double) BAR_HEIGHT;
                int alpha = (int) Math.round(255 * Math.clamp((value - zero) / (full - zero), 0, 1));
                if (alpha > 0) { drawOutputRow(graphics, row, alpha); }
            }
        }
        drawHandle(graphics, true, handleY(zero));
        drawHandle(graphics, false, handleY(full));
        if (Double.isFinite(menu.reading())) {
            double fraction = Math.clamp(controls.position((int) Math.round(menu.reading() * 10)), 0, 1);
            int y = handleY(fraction);
            graphics.fill(leftPos + 16, y, leftPos + 26, y + 1, 0xFFFFF1C5);
        }
    }

    /** Standalone artwork. The optional integration substitutes the original GUI atlas in these three methods. */
    protected void drawPanel(GuiGraphics graphics) {
        graphics.fill(leftPos, topPos, leftPos + 42, topPos + 206, 0xFF222322);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + 41, topPos + 205, 0xFF55564F);
        graphics.fill(leftPos + 14, topPos + 2, leftPos + 28, topPos + 204, 0xFF211615);
        for (int row = 0; row <= BAR_HEIGHT; row += 2) {
            int length = row % 10 == 0 ? 9 : 5;
            graphics.fill(leftPos + 3, topPos + 3 + row, leftPos + 3 + length, topPos + 4 + row, 0xFF282A27);
            graphics.fill(leftPos + 39 - length, topPos + 3 + row, leftPos + 39, topPos + 4 + row, 0xFF282A27);
        }
    }

    protected void drawHandle(GuiGraphics graphics, boolean zero, int centerY) {
        int x = handleX(zero);
        graphics.fill(x, centerY - 8, x + 26, centerY + 8, 0xFF8D642F);
        graphics.fill(x + 1, centerY - 7, x + 25, centerY + 7, 0xFFC0975B);
        graphics.fill(x + 3, centerY - 5, x + 23, centerY + 5, 0xFF8A6F49);
    }

    protected void drawOutputRow(GuiGraphics graphics, int row, int alpha) {
        graphics.fill(leftPos + 16, topPos + 3 + row, leftPos + 26, topPos + 4 + row, alpha << 24 | 0xC42C1C);
    }

    private int handleX(boolean zero) { return leftPos + (zero ? -6 : 22); }
    private int handleY(double fraction) { return topPos + 3 + (int) Math.round((1 - fraction) * BAR_HEIGHT); }

    private boolean overHandle(double mouseX, double mouseY, boolean zero) {
        double visual = zero ? visualZero : visualFull;
        int x = handleX(zero), y = handleY(visual);
        return controls != null && mouseX >= x && mouseX < x + 26 && mouseY >= y - 8 && mouseY < y + 8;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int middle = imageHeight / 2;
        graphics.drawString(font, title, -font.width(title) - 22, middle - 31, 0xFFEAE0CF, false);
        String temperature = Double.isFinite(menu.reading()) ? format((int) Math.round(menu.reading() * 10)) + " " + menu.unit() : "—";
        graphics.drawString(font, temperature, -font.width(temperature) - 22, middle - 16, 0xFFEAE0CF, false);
        if (controls == null) { return; }
        graphics.drawCenteredString(font, "0", 7, -10, 0xFFEAE0CF);
        graphics.drawCenteredString(font, "15", 35, -10, 0xFFEAE0CF);
        drawHandleValue(graphics, mouseX, mouseY, true, controls.zero(), drawnZero);
        drawHandleValue(graphics, mouseX, mouseY, false, controls.full(), drawnFull);
        if (Double.isFinite(menu.reading())) {
            int signal = new SensorRange(controls.zero() / 10.0, controls.full() / 10.0).signal(menu.reading());
            Component output = Component.translatable("gui.meteodyne.sensor.preview", signal);
            graphics.drawString(font, output, -font.width(output) - 22, middle, 0xFFEAE0CF, false);
        }
        graphics.drawWordWrap(font, Component.translatable("gui.meteodyne.sensor.drag"), -122, middle + 23, 100, 0xFFBAB5AC);
        graphics.drawWordWrap(font, Component.translatable(menu.wind() ? "gui.meteodyne.wind_sensor.scroll" : "gui.meteodyne.sensor.scroll"), 64, middle - 28, 100, 0xFFBAB5AC);
        graphics.drawWordWrap(font, Component.translatable(menu.wind() ? "gui.meteodyne.wind_sensor.close" : "gui.meteodyne.sensor.close"), 64, middle + 18, 100, 0xFFBAB5AC);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (controls != null) {
            Boolean zero = overHandle(mouseX, mouseY, true) ? Boolean.TRUE : overHandle(mouseX, mouseY, false) ? Boolean.FALSE : null;
            if (zero != null) {
                graphics.renderTooltip(font, Component.translatable(menu.wind() ? "gui.meteodyne.wind_sensor.endpoint" : "gui.meteodyne.sensor.endpoint", zero ? 0 : 15,
                        format(controls.endpoint(zero))), mouseX, mouseY);
            }
        }
    }

    private void drawHandleValue(GuiGraphics graphics, int mouseX, int mouseY, boolean zero, int value, double visual) {
        boolean highlighted = Boolean.valueOf(zero).equals(dragging) || overHandle(mouseX, mouseY, zero)
                || (keyboardFocus && selectedZero == zero);
        int x = (zero ? 7 : 35), y = handleY(visual) - topPos - font.lineHeight / 2;
        String text = format(value);
        // Extreme fractional values still fit their original 26-pixel handle.
        float scale = Math.min(1, 23f / Math.max(1, font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, -font.width(text) / 2, 0, highlighted ? 0xFFFFF3DE : 0xFF513A23, false);
        graphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        keyboardFocus = false;
        if (button == 0 && controls != null) {
            if (overHandle(mouseX, mouseY, true)) { dragging = selectedZero = true; }
            else if (overHandle(mouseX, mouseY, false)) { dragging = selectedZero = false; }
            if (dragging != null) { return true; }
        }
        // This configuration screen has no inventory slots or outside-click item dropping.
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging != null && button == 0) {
            int target = controls.valueAt(1 - (mouseY - topPos - 3) / BAR_HEIGHT);
            move(dragging, target);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = dragging != null;
        dragging = null;
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (controls == null || deltaY == 0) { return false; }
        Boolean zero = overHandle(mouseX, mouseY, true) ? Boolean.TRUE : overHandle(mouseX, mouseY, false) ? Boolean.FALSE : null;
        if (zero == null) { return false; }
        keyboardFocus = false;
        selectedZero = zero;
        move(zero, controls.endpoint(zero) + (int) Math.signum(deltaY) * (hasShiftDown() ? 1 : 10));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (controls != null && key == GLFW.GLFW_KEY_TAB) {
            keyboardFocus = true;
            selectedZero = !selectedZero;
            narrateEndpoint();
            return true;
        }
        if (controls != null && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            keyboardFocus = true;
            move(selectedZero, controls.endpoint(selectedZero) + (key == GLFW.GLFW_KEY_UP ? 1 : -1) * (hasShiftDown() ? 1 : 10));
            narrateEndpoint();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private void move(boolean zero, int target) {
        if (!controls.move(zero, target, hasControlDown())) { return; }
        int step = (int) Math.floor(controls.position(controls.endpoint(zero)) * 15);
        if (step != soundStep && minecraft != null && minecraft.player != null) {
            soundStep = step;
            minecraft.player.playSound(SoundEvents.LEVER_CLICK, 0.2f,
                    0.25f + (float) controls.position(controls.endpoint(zero)) * 0.5f);
        }
    }

    private void narrateEndpoint() {
        if (minecraft != null) {
            minecraft.getNarrator().sayNow(Component.translatable(menu.wind() ? "gui.meteodyne.wind_sensor.endpoint" : "gui.meteodyne.sensor.endpoint", selectedZero ? 0 : 15,
                    format(controls.endpoint(selectedZero))));
        }
    }

    @Override
    public void onClose() {
        if (!submitted && controls != null && (controls.zero() != initialZero || controls.full() != initialFull)) {
            submitted = true;
            PacketDistributor.sendToServer(new InstrumentNetwork.Configure(menu.containerId, controls.zero(), controls.full()));
        }
        super.onClose();
    }

    private static String format(int tenths) {
        return tenths % 10 == 0 ? Integer.toString(tenths / 10) : String.format(Locale.ROOT, "%.1f", tenths / 10.0);
    }
}
