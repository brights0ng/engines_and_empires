package dev.brights0ng.enginesandempires.geophone.client;

import dev.brights0ng.enginesandempires.geophone.SmartLoggerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The smart logger's reader screen: a wide board in a brass frame, fourteen rows at a time, scrolling through everything
 * the logger holds, with each reading's ore named on its row. See {@link ReadingBoardScreen} for how it works.
 */
public class SmartLoggerScreen extends ReadingBoardScreen<SmartLoggerMenu> {

    static final Layout LAYOUT = new Layout(280, 206, 14, 228, 44, 78, true,
            new int[]{0xFF2A1D0C, 0xFF8A6A2E, 0xFF5C4520});

    public SmartLoggerScreen(SmartLoggerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, LAYOUT);
    }
}
