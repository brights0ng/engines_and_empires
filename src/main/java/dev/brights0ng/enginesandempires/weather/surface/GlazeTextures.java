package dev.brights0ng.enginesandempires.weather.surface;

import java.awt.image.BufferedImage;

/**
 * Glaze's texture (Bright, 2026-10-08): "exactly the minecraft ice texture, but more see-through and less blue, more
 * white". Vanilla's ice, pixel for pixel, pulled toward its own grey and lightened (so its streaks stay where they are),
 * with its alpha cut. Pure Java (the vanilla image is handed in); deterministic; only the data generator uses it.
 */
public final class GlazeTextures {

    /** How much of each pixel's colour turns to its own grey (0: as vanilla, 1: no blue left). */
    static final double DESATURATE = 0.7;
    /** How far the result is pulled toward white. */
    static final double WHITEN = 0.35;
    /** Alpha kept, as a share of vanilla ice's. */
    static final double ALPHA = 0.55;

    public static BufferedImage glaze(BufferedImage ice) {
        BufferedImage out = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                out.setRGB(x, y, pixel(ice.getRGB(x, y)));
            }
        }
        return out;
    }

    static int pixel(int argb) {
        int a = argb >>> 24;
        double r = (argb >> 16) & 0xFF;
        double g = (argb >> 8) & 0xFF;
        double b = argb & 0xFF;
        double grey = 0.299 * r + 0.587 * g + 0.114 * b;
        r = whiten(r + (grey - r) * DESATURATE);
        g = whiten(g + (grey - g) * DESATURATE);
        b = whiten(b + (grey - b) * DESATURATE);
        int alpha = (int) Math.round(a * ALPHA);
        return (alpha << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static double whiten(double c) {
        return c + (255 - c) * WHITEN;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private GlazeTextures() {
    }
}
