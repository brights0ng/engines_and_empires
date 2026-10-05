package dev.brights0ng.enginesandempires.geophone;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mechanical thumper's own textures, drawn in code like {@link SeismicTextures}. Everything else about it (housing,
 * head, cog) uses Create's textures; only the indicator lamp is its own.
 *
 * <p>Dark, the lamp is a plain grey lens. Lit, it is exactly the andesite geophone's lit cap, so the two read as the same
 * kind of light.
 *
 * <p>Pure Java, no Minecraft. Deterministic. Only the data generator uses it.
 */
public final class ThumperTextures {

    /** Every texture: its path under {@code assets/<mod>/textures/}, without the extension, and its image. */
    public static Map<String, BufferedImage> all() {
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        textures.put("block/mechanical_thumper_lamp", darkLamp());
        textures.put("block/mechanical_thumper_lamp_lit", SeismicTextures.cap(true));
        textures.put("block/combustive_thumper_gauge", gauge());
        return textures;
    }

    /**
     * The combustive thumper's fuel gauge dial: a cream face in a dark brass bezel, with tick marks across the top arc the
     * needle sweeps (60 degrees either side of straight up) and a red band at the empty end, on the viewer's left. The
     * needle itself is a separate, tinted model, pivoting on the dial's centre.
     */
    static BufferedImage gauge() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        double cx = 8.0;
        double cy = 8.0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double dx = x + 0.5 - cx;
                double dy = y + 0.5 - cy;
                double r = Math.hypot(dx, dy);
                // Angle from straight up, positive towards the viewer's right, in degrees.
                double angle = Math.toDegrees(Math.atan2(dx, -dy));
                int grain = noise(x, y, 131) % 5 - 2;
                int colour;
                if (r > 7.6) {
                    colour = rgb(150 + grain, 88 + grain, 58 + grain);        // copper surround
                } else if (r > 6.4) {
                    colour = rgb(70 + grain, 52 + grain, 34 + grain);         // dark bezel
                } else {
                    colour = rgb(226 + grain, 214 + grain, 178 + grain);      // cream face
                    boolean onArc = r > 4.2 && r < 6.2 && Math.abs(angle) <= 62;
                    if (onArc && angle < -38) {
                        colour = rgb(196, 48, 38);                            // red: nearly empty
                    } else if (onArc && r > 5.0 && Math.abs(Math.IEEEremainder(angle, 20)) < 5) {
                        colour = rgb(48, 40, 32);                             // tick
                    }
                }
                image.setRGB(x, y, colour);
            }
        }
        return image;
    }

    /** The lamp while off: a plain grey lens. */
    static BufferedImage darkLamp() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int grain = noise(x, y, 97) % 5 - 2;
                image.setRGB(x, y, rgb(60 + grain, 62 + grain, 68 + grain));
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

    private static int noise(int x, int y, int seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return (int) ((h ^ (h >>> 16)) & 0x7FFFFFFF);
    }

    private ThumperTextures() {
    }
}
