package dev.brights0ng.enginesandempires.geophone;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Placeholder pixel art for the seismic tools, drawn in code so the data generator can write it out and
 * nothing has to be copied in by hand. It is meant to be replaced by real art one day: each texture is a
 * plain 16x16 image, and dropping a file of the same name into the assets folder overrides the generated one.
 *
 * <p>Pure Java, no Minecraft. Deterministic: the same code always draws the same pixels. Only the data
 * generator uses it; the game itself never loads this class.
 */
public final class SeismicTextures {

    /** Every texture: its path under {@code assets/<mod>/textures/}, without the extension, and its image. */
    public static Map<String, BufferedImage> all() {
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        textures.put("item/sledgehammer", sledgehammer());
        textures.put("item/andesite_geophone", geophoneItem());
        textures.put("item/brass_geophone", brassGeophoneItem());
        textures.put("block/strike_plate", strikePlate());
        textures.put("block/andesite_geophone", rod());
        textures.put("block/andesite_geophone_guard", guard());
        textures.put("block/andesite_geophone_cap", cap(false));
        textures.put("block/andesite_geophone_cap_lit", cap(true));
        textures.put("block/brass_geophone", brassRod());
        textures.put("block/brass_geophone_guard", brassGuard());
        textures.put("block/brass_geophone_cap", brassCap());
        textures.put("block/brass_geophone_cap_lit", brassCapLit());
        return textures;
    }

    private static final int CLEAR = 0;
    private static final int OUTLINE = rgb(36, 32, 34);

    /** A sledgehammer for the hand: a wooden handle running up to the right, and a heavy iron head across the top. */
    static BufferedImage sledgehammer() {
        BufferedImage image = blank();
        // Handle: a line from the bottom left towards the head.
        double startX = 2.5;
        double startY = 13.5;
        double endX = 9.6;
        double endY = 6.4;
        double length = Math.hypot(endX - startX, endY - startY);
        double dirX = (endX - startX) / length;
        double dirY = (endY - startY) / length;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double px = x + 0.5 - startX;
                double py = y + 0.5 - startY;
                double along = px * dirX + py * dirY;
                double across = Math.abs(px * dirY - py * dirX);
                if (along >= -0.6 && along <= length && across <= 0.95) {
                    boolean band = ((int) Math.floor(along / 2.0)) % 2 == 0;
                    double lift = across < 0.35 ? 1.0 : 0.82;
                    image.setRGB(x, y, shade(band ? rgb(150, 104, 58) : rgb(128, 86, 48), lift));
                }
            }
        }
        // Head: a chunky rotated block across the top of the handle, with darker end faces and a lit top.
        double centreX = 10.9;
        double centreY = 5.1;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double px = x + 0.5 - centreX;
                double py = y + 0.5 - centreY;
                double u = (px + py) / Math.sqrt(2.0);  // along the head
                double v = (px - py) / Math.sqrt(2.0);  // across it
                if (Math.abs(u) <= 3.9 && Math.abs(v) <= 3.1) {
                    int base;
                    if (Math.abs(u) > 2.7) {
                        base = rgb(96, 98, 108);        // the struck faces at either end, worn dark
                    } else if (v < -1.9) {
                        base = rgb(196, 198, 208);      // lit top edge
                    } else if (v > 1.9) {
                        base = rgb(112, 114, 124);      // shaded underside
                    } else {
                        base = rgb(154, 156, 166);
                    }
                    image.setRGB(x, y, shade(base, 1.0 - 0.03 * (px + py)));
                }
            }
        }
        return outline(image);
    }

    /** The geophone as an inventory icon: a rod driven into the ground, capped with the glowing sensor. */
    static BufferedImage geophoneItem() {
        BufferedImage image = blank();
        for (int y = 5; y <= 12; y++) {
            image.setRGB(7, y, rgb(154, 160, 152));
            image.setRGB(8, y, rgb(112, 118, 110));
        }
        image.setRGB(7, 13, rgb(140, 146, 138));
        image.setRGB(8, 13, rgb(100, 106, 98));
        image.setRGB(7, 14, rgb(120, 124, 118));
        image.setRGB(8, 14, rgb(88, 92, 86));
        // Cap.
        for (int y = 2; y <= 5; y++) {
            for (int x = 5; x <= 10; x++) {
                boolean core = x >= 7 && x <= 8 && y >= 3 && y <= 4;
                image.setRGB(x, y, core ? rgb(238, 178, 78) : rgb(74, 78, 88));
            }
        }
        image.setRGB(5, 2, rgb(104, 108, 120));
        image.setRGB(6, 2, rgb(104, 108, 120));
        // A small crossbar at the base, so it reads as staked.
        for (int x = 5; x <= 10; x++) {
            image.setRGB(x, 12, x == 7 || x == 8 ? image.getRGB(x, 12) : rgb(96, 100, 94));
        }
        return outline(image);
    }

    /** A riveted iron plate, seen from above. */
    static BufferedImage strikePlate() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int edge = Math.min(Math.min(x, 15 - x), Math.min(y, 15 - y));
                int grain = noise(x, y, 11) % 9 - 4;
                int base;
                if (edge == 0) {
                    base = rgb(70, 72, 80);
                } else if (edge == 1) {
                    base = (x + y < 15) ? rgb(176, 178, 188) : rgb(112, 114, 124); // bevel: lit top left, shaded bottom right
                } else {
                    base = rgb(146 + grain, 148 + grain, 158 + grain);
                }
                image.setRGB(x, y, base);
            }
        }
        // Rivets in the corners.
        int[][] rivets = {{3, 3}, {12, 3}, {3, 12}, {12, 12}};
        for (int[] rivet : rivets) {
            image.setRGB(rivet[0], rivet[1], rgb(196, 198, 208));
            image.setRGB(rivet[0] + 1, rivet[1], rgb(126, 128, 138));
            image.setRGB(rivet[0], rivet[1] + 1, rgb(126, 128, 138));
            image.setRGB(rivet[0] + 1, rivet[1] + 1, rgb(92, 94, 104));
        }
        // A worn patch in the middle where the hammer lands.
        for (int y = 6; y <= 9; y++) {
            for (int x = 6; x <= 9; x++) {
                int wear = noise(x, y, 23) % 7 - 3;
                image.setRGB(x, y, rgb(122 + wear, 124 + wear, 134 + wear));
            }
        }
        return image;
    }

    /** The rod: rough andesite alloy, greenish grey, lighter on one side. */
    static BufferedImage rod() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int grain = noise(x, y, 5) % 13 - 6;
                double light = 1.0 - 0.02 * (x % 4);
                image.setRGB(x, y, shade(rgb(138 + grain, 144 + grain, 138 + grain), light));
            }
        }
        return image;
    }

    /**
     * The crossguard: dark iron, a shade darker and bluer than the rod so it stands out against it, with a bevel that is
     * lit at the top left and shaded at the bottom right, and a rivet in the middle.
     */
    static BufferedImage guard() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int grain = noise(x, y, 17) % 7 - 3;
                double light = 1.12 - 0.045 * (x + y) / 2.0; // brighter towards the top left
                image.setRGB(x, y, shade(rgb(84 + grain, 88 + grain, 98 + grain), light));
            }
        }
        image.setRGB(7, 7, rgb(150, 154, 166));
        image.setRGB(8, 7, rgb(112, 116, 128));
        image.setRGB(7, 8, rgb(112, 116, 128));
        image.setRGB(8, 8, rgb(70, 74, 84));
        return image;
    }

    /**
     * The sensor cap. Unlit it is dull gunmetal with a faint lens; lit it glows a soft amber, brightest at the
     * middle, which is the part the top of the cap shows.
     */
    static BufferedImage cap(boolean lit) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double distance = Math.hypot(x + 0.5 - 8.0, y + 0.5 - 8.0);
                int grain = noise(x, y, 31) % 5 - 2;
                int colour;
                if (lit) {
                    double glow = Math.max(0.0, 1.0 - distance / 6.5);
                    colour = mix(rgb(214, 132, 52), rgb(255, 236, 170), glow * glow);
                    colour = shade(colour, 1.0 + grain * 0.015);
                } else {
                    double lens = distance < 2.2 ? 0.35 : 0.0;
                    colour = mix(rgb(62 + grain, 66 + grain, 76 + grain), rgb(150, 96, 44), lens);
                }
                image.setRGB(x, y, colour);
            }
        }
        return image;
    }

    // ---- small drawing helpers ----

    // ---- brass geophone ----
    //
    // Brass hears every ore, so its cap has to be able to show any colour. Rather than paint one glowing texture per ore,
    // the cap it glows with is a neutral grey-to-white gradient: GeophoneGlow multiplies it by the ore's colour at render
    // time, the same way vanilla tints grass and leaves, so the one texture ends up looking like whichever ore lit it. The
    // rod, guard and dark cap are its own warm, brassy palette, to read as a different material from the andesite geophone
    // even before anything has lit it.

    /** The brass geophone as an inventory icon: like the andesite one, but a warm brass rod and a plain pale lens. */
    static BufferedImage brassGeophoneItem() {
        BufferedImage image = blank();
        for (int y = 5; y <= 12; y++) {
            image.setRGB(7, y, rgb(214, 176, 96));
            image.setRGB(8, y, rgb(164, 128, 60));
        }
        image.setRGB(7, 13, rgb(196, 158, 82));
        image.setRGB(8, 13, rgb(148, 114, 52));
        image.setRGB(7, 14, rgb(168, 134, 66));
        image.setRGB(8, 14, rgb(128, 98, 44));
        // Cap: a pale, colourless lens, since which ore lights it is not fixed.
        for (int y = 2; y <= 5; y++) {
            for (int x = 5; x <= 10; x++) {
                boolean core = x >= 7 && x <= 8 && y >= 3 && y <= 4;
                image.setRGB(x, y, core ? rgb(240, 236, 224) : rgb(120, 96, 54));
            }
        }
        image.setRGB(5, 2, rgb(150, 118, 58));
        image.setRGB(6, 2, rgb(150, 118, 58));
        // A small crossbar at the base, so it reads as staked.
        for (int x = 5; x <= 10; x++) {
            image.setRGB(x, 12, x == 7 || x == 8 ? image.getRGB(x, 12) : rgb(150, 116, 56));
        }
        return outline(image);
    }

    /** The rod: brass, warmer and more golden than andesite's grey-green alloy. */
    static BufferedImage brassRod() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int grain = noise(x, y, 53) % 13 - 6;
                double light = 1.0 - 0.02 * (x % 4);
                image.setRGB(x, y, shade(rgb(196 + grain, 156 + grain, 74 + grain), light));
            }
        }
        return image;
    }

    /** The crossguard: a darker brass than the rod, with the same lit-corner bevel and central rivet as andesite's. */
    static BufferedImage brassGuard() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int grain = noise(x, y, 59) % 7 - 3;
                double light = 1.12 - 0.045 * (x + y) / 2.0;
                image.setRGB(x, y, shade(rgb(150 + grain, 112 + grain, 50 + grain), light));
            }
        }
        image.setRGB(7, 7, rgb(214, 170, 88));
        image.setRGB(8, 7, rgb(160, 124, 58));
        image.setRGB(7, 8, rgb(160, 124, 58));
        image.setRGB(8, 8, rgb(110, 84, 40));
        return image;
    }

    /** The sensor cap, dark: dull brass with a faint warm lens, matching andesite's shape with a brassy palette. */
    static BufferedImage brassCap() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double distance = Math.hypot(x + 0.5 - 8.0, y + 0.5 - 8.0);
                int grain = noise(x, y, 61) % 5 - 2;
                double lens = distance < 2.2 ? 0.35 : 0.0;
                int colour = mix(rgb(96 + grain, 78 + grain, 42 + grain), rgb(200, 150, 70), lens);
                image.setRGB(x, y, colour);
            }
        }
        return image;
    }

    /**
     * The sensor cap, glowing: a neutral grey-to-white radial gradient, the same shape as andesite's amber glow but with no
     * colour of its own. {@link GeophoneGlow} tints it per ore at render time, so this must stay colourless: any hue baked
     * in here would tint every ore's glow towards it.
     */
    static BufferedImage brassCapLit() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double distance = Math.hypot(x + 0.5 - 8.0, y + 0.5 - 8.0);
                int grain = noise(x, y, 67) % 5 - 2;
                double glow = Math.max(0.0, 1.0 - distance / 6.5);
                int colour = mix(rgb(140 + grain, 140 + grain, 140 + grain), rgb(255, 255, 255), glow * glow);
                image.setRGB(x, y, colour);
            }
        }
        return image;
    }

    // ---- small drawing helpers ----

    private static BufferedImage blank() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Scales a colour's brightness. */
    private static int shade(int argb, double factor) {
        return rgb((int) Math.round(((argb >> 16) & 0xFF) * factor), (int) Math.round(((argb >> 8) & 0xFF) * factor),
                (int) Math.round((argb & 0xFF) * factor));
    }

    private static int mix(int a, int b, double t) {
        return rgb((int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t),
                (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t),
                (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t));
    }

    /** Draws a dark edge around whatever has been drawn, on the empty pixels that touch it. */
    private static BufferedImage outline(BufferedImage image) {
        BufferedImage result = blank();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) != 0) {
                    result.setRGB(x, y, pixel);
                } else if (touches(image, x, y)) {
                    result.setRGB(x, y, OUTLINE);
                } else {
                    result.setRGB(x, y, CLEAR);
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

    /** A small deterministic pseudo-random number from a position, always 0 or more. */
    private static int noise(int x, int y, int seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return (int) ((h ^ (h >>> 16)) & 0x7FFFFFFF);
    }

    private SeismicTextures() {
    }
}
