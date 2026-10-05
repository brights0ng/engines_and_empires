package dev.brights0ng.enginesandempires.geophone;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The smart logger's own textures: the glass of its map screen, and its bronze buttons. Everything else about it uses
 * Create's brass casing; the map itself is drawn over the glass by the renderer.
 *
 * <p>Pure Java, no Minecraft. Deterministic. Only the data generator uses it.
 */
public final class LoggerTextures {

    /** Every texture: its path under {@code assets/<mod>/textures/}, without the extension, and its image. */
    public static Map<String, BufferedImage> all() {
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        textures.put("block/smart_logger_screen", screen());
        textures.put("block/smart_logger_button", button());
        return textures;
    }

    /** Near-black glass, with a soft sheen across one corner. The map is drawn straight onto it, on or off. */
    static BufferedImage screen() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int grain = noise(x, y) % 3 - 1;
                int level = 17 + grain + (x + y < 7 ? 6 : 0);
                image.setRGB(x, y, 0xFF000000 | (clamp(level) << 16) | (clamp(level + 1) << 8) | clamp(level + 2));
            }
        }
        return image;
    }

    /**
     * Bronze, for the buttons: warm and slightly dull, brighter towards the top-left as if lit from there, with a darker rim
     * round the edge of the tile.
     */
    static BufferedImage button() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int grain = noise(x + 31, y + 17) % 7 - 3;
                int shade = (16 - x - y) * 2 + grain;
                int r = 176 + shade;
                int g = 110 + shade * 3 / 4;
                int b = 52 + shade / 2;
                if (x == 0 || y == 0 || x == 15 || y == 15) {
                    r -= 40;
                    g -= 30;
                    b -= 16;
                }
                image.setRGB(x, y, 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }
        return image;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int noise(int x, int y) {
        long h = x * 374761393L + y * 668265263L + 311 * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return (int) ((h ^ (h >>> 16)) & 0x7FFFFFFF);
    }

    private LoggerTextures() {
    }
}
