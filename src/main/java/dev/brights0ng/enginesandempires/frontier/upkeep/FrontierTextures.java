package dev.brights0ng.enginesandempires.frontier.upkeep;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Placeholder pixel art for Frontier's blocks, drawn in code like the seismic tools' (dropping a real file of the same
 * name into the assets folder overrides it).
 *
 * <p>The dry torch is laid out like vanilla's torch texture, since it uses the same torch models: the stick is the 2-pixel
 * column at x 7–8, y 6–15, and its top two rows are what shows as the flame. Here they are charred black instead.
 *
 * <p>Pure Java, no Minecraft. Deterministic.
 */
public final class FrontierTextures {

    public static Map<String, BufferedImage> all() {
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        textures.put("block/dry_torch", dryTorch());
        textures.put("item/squelching_heart", squelchingHeart());
        return textures;
    }

    /**
     * The Squelching Heart: a Warden's heart, in the colours of vanilla's pulsing Warden heart texture (near-black, deep
     * teal, sculk teal, and the bright cyan of its glow), with its vessels at the top.
     */
    static BufferedImage squelchingHeart() {
        String[] rows = {
                "................",
                ".....kk...kk....",
                "....kmdk.kmdk...",
                "....kdmk.kdmk...",
                "...kkdmkkkmdkk..",
                "..kdmmmdddmmmdk.",
                "..kmmllllmllmmk.",
                ".kdmllggglllmmdk",
                ".kdmlggggglllmdk",
                ".kdmlgggglllmmdk",
                "..kmllgglllmmdk.",
                "..kdmlllllmmmdk.",
                "...kdmmllmmmdk..",
                "....kddmmmddk...",
                ".....kkddddk....",
                ".......kkkk.....",
        };
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int colour = switch (rows[y].charAt(x)) {
                    case 'k' -> 0xFF0D1217;
                    case 'd' -> 0xFF0B212A;
                    case 'm' -> 0xFF05625D;
                    case 'l' -> 0xFF009295;
                    case 'g' -> 0xFF29DFEB;
                    default -> 0;
                };
                image.setRGB(x, y, colour);
            }
        }
        return image;
    }

    static BufferedImage dryTorch() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        // The burnt tip: soot on top, charred wood under it.
        image.setRGB(7, 6, 0xFF2B2623);
        image.setRGB(8, 6, 0xFF1F1B19);
        image.setRGB(7, 7, 0xFF3F3630);
        image.setRGB(8, 7, 0xFF332B26);
        // Where the burning stopped: scorched brown.
        image.setRGB(7, 8, 0xFF5E4731);
        image.setRGB(8, 8, 0xFF4E3B28);
        // The stick: lit side and shaded side, a little darker towards the bottom.
        for (int y = 9; y <= 15; y++) {
            int shade = (y - 9) * 3;
            image.setRGB(7, y, argb(0x8C - shade, 0x6A - shade, 0x3E - shade / 2));
            image.setRGB(8, y, argb(0x6B - shade, 0x4F - shade, 0x2B - shade / 2));
        }
        return image;
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int c) {
        return Math.max(0, Math.min(255, c));
    }

    private FrontierTextures() {
    }
}
