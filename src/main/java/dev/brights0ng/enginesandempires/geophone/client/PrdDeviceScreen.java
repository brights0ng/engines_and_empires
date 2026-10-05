package dev.brights0ng.enginesandempires.geophone.client;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The input side of working the portable record display. It draws nothing at all: the display, held up in both hands, is
 * the interface, drawn by {@link PrdItemRenderer}. Being a screen frees the cursor and brings the mouse and keyboard here,
 * and everything is passed straight to {@link PrdSession}, along with the line of sight under the cursor.
 *
 * <p>The game does not pause, and the player can still walk: see {@link PrdClient}.
 *
 * <p>Keys: while a name is being typed, every key goes to it (Enter keeps it, Esc leaves it be). Otherwise Esc or the
 * inventory key put the display down; the inventory key does not open the inventory. {@link PrdClient#LOOK} (Tab) is
 * watched by {@link PrdClient}; here it is only kept from moving focus between widgets, as Tab does on other screens.
 */
public class PrdDeviceScreen extends Screen {

    public PrdDeviceScreen() {
        super(Component.translatable("item.engines_and_empires.portable_record_display"));
    }

    private double[] ray(double mouseX, double mouseY) {
        return PrdSession.ray(mouseX, mouseY, width, height);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Nothing: not even the usual dimming, so the world and the display show as they are. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Nothing to draw; each frame, works out what the cursor is over. */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        PrdSession.hover(ray(mouseX, mouseY));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        PrdSession.click(ray(mouseX, mouseY), button, hasShiftDown());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        PrdSession.release(button);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            PrdSession.drag(ray(mouseX, mouseY));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        PrdSession.scrolled(ray(mouseX, mouseY), scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (PrdSession.typing()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> PrdSession.confirmName();
                case GLFW.GLFW_KEY_ESCAPE -> PrdSession.cancelName();
                case GLFW.GLFW_KEY_BACKSPACE -> PrdSession.backspace();
                default -> {
                }
            }
            return true; // every other key is typing, so none of them does anything else
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE
                || (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode))) {
            PrdSession.end();
            return true;
        }
        return true; // Tab and the rest: nothing to move between, and nothing else to do
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        PrdSession.typed(codePoint);
        return true;
    }

    /** Closed by the game rather than by putting the display down: put it down all the same. */
    @Override
    public void onClose() {
        PrdSession.end();
    }
}
