package dev.brights0ng.enginesandempires.geophone;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Placeholder pixel art for the logbook, drawn in code like {@link SeismicTextures}, and replaceable in the same way. The
 * screen itself is drawn in code too, and needs no textures.
 *
 * <p>Pure Java, no Minecraft. Deterministic. Only the data generator uses it.
 */
public final class LogbookTextures {

    /** Every texture: its path under {@code assets/<mod>/textures/}, without the extension, and its image. */
    public static Map<String, BufferedImage> all() {
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        textures.put("item/logbook", book());
        return textures;
    }

    private static final int OUTLINE = rgb(36, 28, 22);

    /** A leather-bound book with brass corners, and on its cover a small board of split-flap rows like a display board. */
    static BufferedImage book() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        // The cover.
        for (int y = 1; y <= 14; y++) {
            for (int x = 2; x <= 13; x++) {
                int grain = noise(x, y, 71) % 7 - 3;
                int leather = x <= 3 ? rgb(92, 58, 36)                          // the spine, darker
                        : (x + y < 14 ? rgb(132, 86, 52) : rgb(112, 72, 44));   // lit top left, shaded bottom right
                image.setRGB(x, y, shade(leather, 1.0 + grain * 0.025));
            }
        }
        // The pages, showing along the bottom and the right-hand edge.
        for (int x = 4; x <= 13; x++) {
            image.setRGB(x, 14, rgb(226, 214, 180));
        }
        for (int y = 2; y <= 13; y++) {
            image.setRGB(13, y, rgb(214, 202, 168));
        }
        // Brass bands across the spine.
        for (int y : new int[]{3, 12}) {
            image.setRGB(2, y, rgb(214, 168, 78));
            image.setRGB(3, y, rgb(240, 200, 108));
        }
        // Brass corners.
        image.setRGB(12, 1, rgb(214, 168, 78));
        image.setRGB(13, 1, rgb(240, 200, 108));
        image.setRGB(12, 13, rgb(190, 146, 62));
        // The board: a dark panel with three rows of amber flaps, each row a little different, so it reads as a display.
        for (int y = 3; y <= 10; y++) {
            for (int x = 5; x <= 11; x++) {
                boolean edge = x == 5 || x == 11 || y == 3 || y == 10;
                image.setRGB(x, y, edge ? rgb(62, 64, 72) : rgb(30, 32, 38));
            }
        }
        int amber = rgb(238, 182, 66);
        for (int x : new int[]{6, 7, 9, 10}) {
            image.setRGB(x, 5, amber);
        }
        for (int x = 6; x <= 10; x++) {
            image.setRGB(x, 7, amber);
        }
        for (int x : new int[]{6, 7, 8}) {
            image.setRGB(x, 9, amber);
        }
        return outline(image);
    }

    // ---- small drawing helpers ----

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int shade(int argb, double factor) {
        return rgb((int) Math.round(((argb >> 16) & 0xFF) * factor), (int) Math.round(((argb >> 8) & 0xFF) * factor),
                (int) Math.round((argb & 0xFF) * factor));
    }

    /** Draws a dark edge around whatever has been drawn, on the empty pixels that touch it. */
    private static BufferedImage outline(BufferedImage image) {
        BufferedImage result = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) != 0) {
                    result.setRGB(x, y, pixel);
                } else if (touches(image, x, y)) {
                    result.setRGB(x, y, OUTLINE);
                }
            }
        }
        return result;
    }

    private static boolean touches(BufferedImage image, int x, int y) {
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] step : steps) {
            int nx = x + step[0];
            int ny = y + step[1];
            if (nx >= 0 && ny >= 0 && nx < 16 && ny < 16 && (image.getRGB(nx, ny) >>> 24) != 0) {
                return true;
            }
        }
        return false;
    }

    private static int noise(int x, int y, int seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return (int) ((h ^ (h >>> 16)) & 0x7FFFFFFF);
    }

    private LogbookTextures() {
    }
}
