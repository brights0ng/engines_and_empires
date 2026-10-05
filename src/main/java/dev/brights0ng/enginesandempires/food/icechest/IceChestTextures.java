package dev.brights0ng.enginesandempires.food.icechest;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The ice chest's textures, made from vanilla's (Bright's look, 2026-09-29): the barrel, framed in iron beams along every
 * edge, with an iron lid; open, the lid gives way to an iron rim around white wool lining.
 *
 * <p>Built from the vanilla barrel, iron block and white wool textures (handed in, so this stays plain Java with no
 * Minecraft). Deterministic. Only the data generator uses it.
 */
public final class IceChestTextures {

    /** The vanilla textures this reads, by the name {@link #all} asks for them with. */
    public static final String BARREL_SIDE = "barrel_side";
    public static final String BARREL_BOTTOM = "barrel_bottom";
    public static final String IRON = "iron_block";
    public static final String WOOL = "white_wool";

    /** How wide the iron beams along the edges are, in pixels. */
    private static final int BEAM = 2;

    /** Every texture: its path under {@code assets/<mod>/textures/}, without the extension, and its image. */
    public static Map<String, BufferedImage> all(Function<String, BufferedImage> vanilla) {
        BufferedImage iron = vanilla.apply(IRON);
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        textures.put("block/ice_chest_side", framed(vanilla.apply(BARREL_SIDE), iron));
        textures.put("block/ice_chest_bottom", framed(vanilla.apply(BARREL_BOTTOM), iron));
        textures.put("block/ice_chest_top", lid(iron));
        textures.put("block/ice_chest_top_open", open(iron, vanilla.apply(WOOL)));
        return textures;
    }

    /** A barrel face with iron beams along all four edges, their outer pixel darker and a rivet at each corner. */
    static BufferedImage framed(BufferedImage face, BufferedImage iron) {
        BufferedImage out = copy(face);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int edge = edgeDistance(x, y);
                if (edge < BEAM) {
                    out.setRGB(x, y, shade(iron.getRGB(x, y), edge == 0 ? 0.62 : 0.85));
                }
            }
        }
        rivets(out, iron);
        return out;
    }

    /** The closed lid: plain iron, darker at the very edge, with a seam where the lid meets the frame and a rivet at each corner. */
    static BufferedImage lid(BufferedImage iron) {
        BufferedImage out = copy(iron);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int edge = edgeDistance(x, y);
                if (edge == 0) {
                    out.setRGB(x, y, shade(iron.getRGB(x, y), 0.72));
                } else if (edge == BEAM) {
                    out.setRGB(x, y, shade(iron.getRGB(x, y), 0.82));
                }
            }
        }
        rivets(out, iron);
        return out;
    }

    /** Open: the iron rim, a shadow along its inside, and the wool lining within (a little darker, being inside). */
    static BufferedImage open(BufferedImage iron, BufferedImage wool) {
        BufferedImage out = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int edge = edgeDistance(x, y);
                int colour;
                if (edge < BEAM) {
                    colour = shade(iron.getRGB(x, y), edge == 0 ? 0.62 : 0.85);
                } else if (edge == BEAM) {
                    colour = shade(wool.getRGB(x, y), 0.55);
                } else if (edge == BEAM + 1 && (x == BEAM + 1 || y == BEAM + 1)) {
                    // Light comes from the upper left, so the rim shades the lining's top and left a little further in
                    colour = shade(wool.getRGB(x, y), 0.72);
                } else {
                    colour = shade(wool.getRGB(x, y), 0.88);
                }
                out.setRGB(x, y, colour);
            }
        }
        rivets(out, iron);
        return out;
    }

    private static void rivets(BufferedImage out, BufferedImage iron) {
        int[][] corners = {{1, 1}, {14, 1}, {1, 14}, {14, 14}};
        for (int[] c : corners) {
            out.setRGB(c[0], c[1], shade(iron.getRGB(c[0], c[1]), 1.18));
        }
    }

    /** How many pixels in from the nearest edge. */
    private static int edgeDistance(int x, int y) {
        return Math.min(Math.min(x, 15 - x), Math.min(y, 15 - y));
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage out = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                out.setRGB(x, y, source.getRGB(x, y));
            }
        }
        return out;
    }

    static int shade(int argb, double factor) {
        int a = argb >>> 24;
        int r = clamp((int) Math.round(((argb >> 16) & 0xFF) * factor));
        int g = clamp((int) Math.round(((argb >> 8) & 0xFF) * factor));
        int b = clamp((int) Math.round((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private IceChestTextures() {
    }
}
