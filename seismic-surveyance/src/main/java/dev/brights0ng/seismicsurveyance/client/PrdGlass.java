// Edited by Gemini: added ORES/OIL/FRONTIER/EMPIRE filter tabs with scaled font layout.
package dev.brights0ng.seismicsurveyance.client;

import dev.brights0ng.seismicsurveyance.data.LogbookEntry;
import dev.brights0ng.seismicsurveyance.menu.ReadingBoardMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The layout of what is drawn on the portable record display's glass that depends on the font: the tabs along its top.
 * Positions are the map screen's pixels from the glass's top left (see {@code PrdShape.GLASS_WIDTH}); the tabs are one font
 * line high, and sized to their words.
 */
final class PrdGlass {

    static final float TAB_FONT_SCALE = 0.75F;
    static final int TAB_PAD = 2;
    static final int TAB_GAP = 1;
    static final int TAB_LEFT = 2;
    static final int SECTION_GAP = 2;

    static Component label(PrdSession.Page page) {
        return Component.translatable("gui.seismic_surveyance.portable_record_display.tab_" + page.name().toLowerCase(java.util.Locale.ROOT));
    }

    static Component filterLabel(PrdSession.Filter filter) {
        return Component.translatable("gui.seismic_surveyance.portable_record_display.tab_" + filter.name().toLowerCase(java.util.Locale.ROOT));
    }

    static int tabWidth(Component label) {
        return Math.round(Minecraft.getInstance().font.width(label) * TAB_FONT_SCALE) + 2 * TAB_PAD;
    }

    /** Where a page's tab is: {left, right}. */
    static int[] tab(PrdSession.Page page) {
        int x = TAB_LEFT;
        for (PrdSession.Page each : PrdSession.Page.values()) {
            int width = tabWidth(label(each));
            if (each == page) {
                return new int[]{x, x + width};
            }
            x += width + TAB_GAP;
        }
        throw new IllegalArgumentException(String.valueOf(page));
    }

    /** Where the page tabs end. */
    static int pageTabsEnd() {
        int x = TAB_LEFT;
        for (PrdSession.Page each : PrdSession.Page.values()) {
            int width = tabWidth(label(each));
            x += width + TAB_GAP;
        }
        return x - TAB_GAP;
    }

    /** Where a filter's tab is: {left, right}. */
    static int[] filterTab(PrdSession.Filter filter) {
        int x = pageTabsEnd() + SECTION_GAP;
        for (PrdSession.Filter each : PrdSession.Filter.values()) {
            int width = tabWidth(filterLabel(each));
            if (each == filter) {
                return new int[]{x, x + width};
            }
            x += width + TAB_GAP;
        }
        throw new IllegalArgumentException(String.valueOf(filter));
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

    /** The filter tab at {@code x} along the tab bar, or null. */
    static PrdSession.Filter filterTabAt(double x) {
        for (PrdSession.Filter filter : PrdSession.Filter.values()) {
            int[] bounds = filterTab(filter);
            if (x >= bounds[0] && x < bounds[1]) {
                return filter;
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
