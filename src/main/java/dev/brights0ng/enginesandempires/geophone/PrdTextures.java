package dev.brights0ng.enginesandempires.geophone;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The portable record display's own textures, drawn in code like the rest of the seismic kit's, and replaceable the same way:
 * the three lamps' lenses (dark: the renderer lights them), and the screen as it looks in the inventory (the renderer draws
 * the live map over it when the display is held). Everything else is Create's metal (see {@link PrdShape.Tex}), so is not
 * drawn here.
 *
 * <p>Pure Java, no Minecraft. Deterministic. Only the data generator uses it.
 */
public final class PrdTextures {

    /** Every texture of its own: its path under {@code assets/<mod>/textures/}, without the extension, and its image. */
    public static Map<String, BufferedImage> all() {
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        for (PrdShape.Tex tex : PrdShape.Tex.values()) {
            if (!tex.borrowed()) {
                textures.put(tex.path(), draw(tex));
            }
        }
        return textures;
    }

    /** The lenses' colours while dark, left to right: red, yellow, green. */
    static final int[][] LENS_DARK = {{112, 36, 30}, {118, 96, 32}, {34, 98, 46}};

    static BufferedImage draw(PrdShape.Tex tex) {
        return switch (tex) {
            case SCREEN -> screen();
            case LENS_RED -> lens(LENS_DARK[0]);
            case LENS_YELLOW -> lens(LENS_DARK[1]);
            case LENS_GREEN -> lens(LENS_DARK[2]);
            case CASING, TRIM, GRIP, DARK -> throw new IllegalArgumentException(tex + " is borrowed: " + tex.location());
        };
    }

    /**
     * The screen as it shows in the inventory, in an item frame and on the ground: the map's dark glass, a faint grid, a few
     * readings in their ores' colours, and the white arrow of whoever holds it.
     */
    private static BufferedImage screen() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean grid = x % 5 == 2 || y % 5 == 2;
                int sheen = x + y < 7 ? 5 : 0;
                image.setRGB(x, y, grid ? rgb(34, 44, 42) : rgb(16 + sheen, 20 + sheen, 20 + sheen));
            }
        }
        image.setRGB(4, 4, rgb(232, 153, 141));   // iron
        image.setRGB(11, 3, rgb(240, 200, 80));   // gold
        image.setRGB(12, 11, rgb(110, 230, 220)); // diamond
        image.setRGB(3, 12, rgb(224, 120, 70));   // copper
        int white = rgb(232, 236, 234);
        int[][] arrow = {{7, 6}, {8, 6}, {6, 7}, {7, 7}, {8, 7}, {9, 7}, {7, 8}, {8, 8}};
        for (int[] p : arrow) {
            image.setRGB(p[0], p[1], white);
        }
        return image;
    }

    /** A dark lens: its colour, deeper at the rim, with one glint. */
    private static BufferedImage lens(int[] colour) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double edge = (x < 2 || y < 2 || x > 13 || y > 13) ? 0.55 : 1.0;
                image.setRGB(x, y, rgb((int) (colour[0] * edge), (int) (colour[1] * edge), (int) (colour[2] * edge)));
            }
        }
        for (int y = 3; y <= 5; y++) {
            for (int x = 3; x <= 5; x++) {
                image.setRGB(x, y, rgb(colour[0] + 70, colour[1] + 70, colour[2] + 70));
            }
        }
        return image;
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private PrdTextures() {
    }
}
