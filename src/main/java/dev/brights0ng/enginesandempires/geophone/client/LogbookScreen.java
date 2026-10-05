package dev.brights0ng.enginesandempires.geophone.client;

import dev.brights0ng.enginesandempires.geophone.Logbook;
import dev.brights0ng.enginesandempires.geophone.LogbookMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The logbook screen: a board of twelve rows in a dark grey book, one per saved reading. See {@link ReadingBoardScreen}
 * for how it works.
 */
public class LogbookScreen extends ReadingBoardScreen<LogbookMenu> {

    private static final Layout LAYOUT = new Layout(224, 160, Logbook.MAX_ENTRIES, 176, 40, 52, false,
            new int[]{0xFF17181B, 0xFF3A3C43, 0xFF2E3036});

    public LogbookScreen(LogbookMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, LAYOUT);
    }
}
