package dev.brights0ng.enginesandempires.geophone.client;

import dev.brights0ng.enginesandempires.geophone.PrdMenu;
import dev.brights0ng.enginesandempires.geophone.PrdSignal;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The portable record display's records list: the smart logger's board, in the display's dark steel frame, with the
 * display's three lamps along the top so a save's outcome shows here as well as on the item. See
 * {@link ReadingBoardScreen} for how it works.
 */
public class PrdScreen extends ReadingBoardScreen<PrdMenu> {

    private static final Layout LAYOUT = new Layout(280, 206, 14, 228, 44, 96, true,
            new int[]{0xFF101216, 0xFF6B5A36, 0xFF24272D});

    public PrdScreen(PrdMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, LAYOUT);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        PrdLamps.draw(graphics, 70, 7, PrdClient.light(menu.itemStack(), null));
    }

    /** The three lamps, drawn the same wherever the display's face is shown on screen. */
    static final class PrdLamps {

        static void draw(GuiGraphics graphics, int x, int y, PrdSignal.Light lit) {
            lamp(graphics, x, y, lit == PrdSignal.Light.RED, 0xFFFF4A3A, 0xFF4A1A16);
            lamp(graphics, x + 7, y, lit == PrdSignal.Light.YELLOW, 0xFFFFD23A, 0xFF4A3A14);
            lamp(graphics, x + 14, y, lit == PrdSignal.Light.GREEN, 0xFF5CFF7A, 0xFF163A1E);
        }

        private static void lamp(GuiGraphics graphics, int x, int y, boolean lit, int on, int off) {
            graphics.fill(x - 1, y - 1, x + 5, y + 4, 0xFF0A0B0D);
            graphics.fill(x, y, x + 4, y + 3, lit ? on : off);
            if (lit) {
                graphics.fill(x, y, x + 2, y + 1, 0xFFFFFFFF);
            }
        }

        private PrdLamps() {
        }
    }
}
