package dev.brights0ng.enginesandempires.geophone.client;

import dev.brights0ng.enginesandempires.geophone.LogbookEntry;
import dev.brights0ng.enginesandempires.geophone.ReadingBoardMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The layout of what is drawn on the portable record display's glass that depends on the font: the tabs along its top.
 * Positions are the map screen's pixels from the glass's top left (see {@code PrdShape.GLASS_WIDTH}); the tabs are one font
 * line high, and sized to their words.
 */
final class PrdGlass {

    /** Space either side of a tab's word, and between tabs. */
    static final int TAB_PAD = 4;
    static final int TAB_GAP = 2;
    static final int TAB_LEFT = 2;

    static Component label(PrdSession.Page page) {
        return Component.translatable("gui.engines_and_empires.portable_record_display.tab_" + page.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Where a page's tab is: {left, right}. */
    static int[] tab(PrdSession.Page page) {
        int x = TAB_LEFT;
        for (PrdSession.Page each : PrdSession.Page.values()) {
            int width = Minecraft.getInstance().font.width(label(each)) + 2 * TAB_PAD;
            if (each == page) {
                return new int[]{x, x + width};
            }
            x += width + TAB_GAP;
        }
        throw new IllegalArgumentException(String.valueOf(page));
    }

    /** The tab at {@code x} along the tab bar, or null. */
    static PrdSession.Page tabAt(double x) {
        for (PrdSession.Page page : PrdSession.Page.values()) {
            int[] bounds = tab(page);
            if (x >= bounds[0] && x < bounds[1]) {
                return page;
            }
        }
        return null;
    }

    /** What a reading is called, as plain text. */
    static String name(LogbookEntry entry) {
        return ReadingBoardMenu.displayName(entry).getString();
    }

    private PrdGlass() {
    }
}
