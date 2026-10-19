// Sized-panel designs by Bright; original base tile, procedural recreation and face layout rules written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Metal at one texel per model pixel: preserve the rim, remove middle material, and collapse touching bolt rows.
 * Logical panels sit at the top left of a 16x16 sprite, retaining normal atlas mipmaps and allowing exact UV crops.
 * No reference PNG is read by the generator.
 */
public final class InstrumentMetalTextures {
    public static final int SPRITE_SIZE = 16;
    public static final int EDGE = 0xFF515755, RIM = 0xFF747A70, INSET = 0xFF63695F, CORE = 0xFF696F65;
    public static final int BOLT = 0xFFAD8848, HIGHLIGHT = 0xFFD4B570;
    public static final int BOLT_SIZE = 2, BOLT_INSET = 2;

    /** Long dimension first. Portrait model faces rotate the UVs rather than stretching these panels. */
    public enum Panel {
        BASE("instrument_metal", 16, 16),
        BASE_SIDE("instrument_base_side", 16, 2),
        SUPPORT_FRONT("instrument_support_front", 14, 8),
        SUPPORT_SIDE("instrument_support_side", 8, 4),
        SUPPORT_TOP("instrument_support_top", 14, 4),
        SUPPORT_END("instrument_support_end", 4, 4),
        HEAD_BACK("temperature_sensor_back", 13, 6, true),
        HEAD_SIDE("temperature_sensor_side", 13, 2),
        WIND_BACK("wind_sensor_back", 6, 6),
        WIND_MAST("wind_sensor_mast", 5, 2),
        WIND_SHORT_MAST("wind_sensor_short_mast", 3, 2),
        WIND_MAST_TOP("wind_sensor_mast_top", 2, 2),
        HEAD_TOP("temperature_sensor_top", 6, 2);

        private final String path;
        private final int width, height;
        private final boolean reverseRightHighlight;

        Panel(String path, int width, int height) { this(path, width, height, false); }
        Panel(String path, int width, int height, boolean reverseRightHighlight) {
            this.path = path;
            this.width = width;
            this.height = height;
            this.reverseRightHighlight = reverseRightHighlight;
        }

        public String path() { return path; }
        public int width() { return width; }
        public int height() { return height; }

        public static Panel forFace(int width, int height) {
            int longSide = Math.max(width, height), shortSide = Math.min(width, height);
            for (Panel panel : values()) {
                if (panel.width == longSide && panel.height == shortSide) { return panel; }
            }
            throw new IllegalArgumentException("No metal panel defined for " + width + "x" + height);
        }
    }

    /** Two edge bolts need a clear pixel between them; otherwise use one centered bolt, or none below six pixels. */
    public static List<Integer> boltAnchors(int length) {
        if (length < BOLT_INSET * 2 + BOLT_SIZE) { return List.of(); }
        int first = BOLT_INSET, last = length - BOLT_INSET - BOLT_SIZE;
        return last - (first + BOLT_SIZE) >= 1 ? List.of(first, last) : List.of((length - BOLT_SIZE) / 2);
    }

    /** Recreates the logical panel with edge-color padding. Padding is outside the model's UV rectangle. */
    public static BufferedImage panel(Panel panel) {
        BufferedImage image = new BufferedImage(SPRITE_SIZE, SPRITE_SIZE, BufferedImage.TYPE_INT_ARGB);
        fill(image, 0, 0, SPRITE_SIZE, SPRITE_SIZE, EDGE);
        int width = panel.width, height = panel.height;
        fill(image, 1, 1, width - 2, height - 2, RIM);
        // A six-pixel panel has only a two-pixel inner field: retain its core color without a compressed inset band.
        fill(image, 2, 2, width - 4, height - 4, height == 6 ? CORE : INSET);
        fill(image, 3, 3, width - 6, height - 6, CORE);
        List<Integer> xs = boltAnchors(width), ys = boltAnchors(height);
        for (int x : xs) {
            for (int y : ys) {
                fill(image, x, y, BOLT_SIZE, BOLT_SIZE, BOLT);
                // Bright's compact back reference has the right bolt's highlight on its lower-left pixel.
                int highlightY = panel.reverseRightHighlight && x == xs.getLast() ? y + 1 : y;
                image.setRGB(x, highlightY, HIGHLIGHT);
            }
        }
        return image;
    }

    private static void fill(BufferedImage image, int x, int y, int width, int height, int color) {
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) { image.setRGB(px, py, color); }
        }
    }

    private InstrumentMetalTextures() { }
}
