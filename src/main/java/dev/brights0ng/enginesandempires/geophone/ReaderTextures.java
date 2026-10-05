package dev.brights0ng.enginesandempires.geophone;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Placeholder pixel art for the wind-up reader, drawn in code like {@link SeismicTextures}, and replaceable in the same way.
 *
 * <p>The held item is a brass-cased dial with a crank on one side. It has one picture for each of the {@link ReaderCompass#FRAMES}
 * positions of the needle, drawn clockwise from straight up, and a picture for when it has nothing to point at. A small arrow
 * for the height of the reading is drawn on a separate layer, in the strip above the case, so it can be put over any frame.
 * The placed reader is drawn from three block textures: the casing, and its lamp dark and lit.
 *
 * <p>Pure Java, no Minecraft. Deterministic. Only the data generator uses it.
 */
public final class ReaderTextures {

    /** Every texture: its path under {@code assets/<mod>/textures/}, without the extension, and its image. */
    public static Map<String, BufferedImage> all() {
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        for (int frame = 0; frame < ReaderCompass.FRAMES; frame++) {
            textures.put("item/windup_reader_" + frame, dial(frame));
        }
        textures.put("item/windup_reader_idle", dial(-1));
        textures.put("item/windup_reader_arrow_up", arrow(ReaderCompass.HeightBand.UP));
        textures.put("item/windup_reader_arrow_level", arrow(ReaderCompass.HeightBand.LEVEL));
        textures.put("item/windup_reader_arrow_down", arrow(ReaderCompass.HeightBand.DOWN));
        textures.put("block/windup_reader", casing());
        textures.put("block/windup_reader_lamp", lamp(false));
        textures.put("block/windup_reader_lamp_lit", lamp(true));
        return textures;
    }

    private static final int CLEAR = 0;
    private static final int OUTLINE = rgb(40, 32, 26);

    // The dial's middle, in pixels. The case is below the strip at the top that the height arrow uses.
    private static final double CENTRE_X = 8.0;
    private static final double CENTRE_Y = 9.0;
    private static final double DIAL_RADIUS = 4.6;
    private static final double NEEDLE_LENGTH = 4.5;

    /**
     * The held item with the needle at one of its positions, or with no needle if {@code frame} is negative. The needle turns
     * clockwise from straight up, so frame 0 points up and frame {@code FRAMES / 4} points right.
     */
    static BufferedImage dial(int frame) {
        BufferedImage image = blank();
        // The case: a brass box below the top strip.
        for (int y = 3; y <= 14; y++) {
            for (int x = 2; x <= 13; x++) {
                int edge = Math.min(Math.min(x - 2, 13 - x), Math.min(y - 3, 14 - y));
                int grain = noise(x, y, 41) % 7 - 3;
                int brass = edge == 0 ? rgb(150, 108, 44)
                        : (x + y < 16 ? rgb(226, 178, 86) : rgb(176, 128, 56));
                image.setRGB(x, y, edge == 0 ? brass : shade(brass, 1.0 + grain * 0.02));
            }
        }
        // The crank: a short axle out of the left side, and a grip on the end of it.
        image.setRGB(1, 9, rgb(92, 92, 100));
        for (int y = 6; y <= 11; y++) {
            image.setRGB(0, y, y == 6 || y == 11 ? rgb(70, 70, 78) : rgb(128, 128, 138));
        }
        // The dial face.
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double distance = Math.hypot(x + 0.5 - CENTRE_X, y + 0.5 - CENTRE_Y);
                if (distance <= DIAL_RADIUS) {
                    int face = frame < 0 ? rgb(150, 148, 140) : rgb(232, 222, 190);
                    image.setRGB(x, y, distance > DIAL_RADIUS - 1.0 ? shade(face, 0.82) : face);
                }
            }
        }
        if (frame >= 0) {
            // The needle, made of short steps so it never has gaps: a red half pointing the way, a dark tail behind it.
            double angle = Math.toRadians(360.0 * frame / ReaderCompass.FRAMES);
            double dx = Math.sin(angle);
            double dy = -Math.cos(angle);
            for (double t = -2.2; t <= NEEDLE_LENGTH; t += 0.2) {
                int px = (int) Math.floor(CENTRE_X + dx * t);
                int py = (int) Math.floor(CENTRE_Y + dy * t);
                if (t < 0.0) {
                    image.setRGB(px, py, rgb(58, 56, 64));
                } else {
                    image.setRGB(px, py, t > NEEDLE_LENGTH - 1.6 ? rgb(214, 48, 40) : rgb(178, 44, 38));
                }
            }
            image.setRGB((int) Math.floor(CENTRE_X), (int) Math.floor(CENTRE_Y), rgb(226, 178, 86)); // the pivot
        } else {
            // Nothing to point at: a dead dial, with a small dark dot where the pivot is.
            image.setRGB((int) Math.floor(CENTRE_X), (int) Math.floor(CENTRE_Y), rgb(58, 56, 64));
        }
        return outline(image);
    }

    /** The height arrow, in the strip along the top: pointing up, pointing down, or two bars for level. */
    static BufferedImage arrow(ReaderCompass.HeightBand band) {
        BufferedImage image = blank();
        int bright = rgb(255, 214, 64);
        switch (band) {
            case UP -> {
                image.setRGB(11, 0, bright);
                for (int x = 10; x <= 12; x++) {
                    image.setRGB(x, 1, bright);
                }
                for (int x = 9; x <= 13; x++) {
                    image.setRGB(x, 2, bright);
                }
            }
            case DOWN -> {
                for (int x = 9; x <= 13; x++) {
                    image.setRGB(x, 0, bright);
                }
                for (int x = 10; x <= 12; x++) {
                    image.setRGB(x, 1, bright);
                }
                image.setRGB(11, 2, bright);
            }
            case LEVEL -> {
                for (int x = 9; x <= 13; x++) {
                    image.setRGB(x, 0, bright);
                    image.setRGB(x, 2, bright);
                }
            }
            default -> {
            }
        }
        return image;
    }

    /** The placed reader's casing: brass plate with a border and rivets. Seen from every side, so it has no picture on it. */
    static BufferedImage casing() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int edge = Math.min(Math.min(x, 15 - x), Math.min(y, 15 - y));
                int grain = noise(x, y, 53) % 7 - 3;
                int base = edge == 0 ? rgb(132, 92, 36)
                        : edge == 1 ? ((x + y < 15) ? rgb(236, 190, 98) : rgb(160, 114, 48))
                        : rgb(202 + grain, 156 + grain, 72 + grain);
                image.setRGB(x, y, base);
            }
        }
        int[][] rivets = {{3, 3}, {11, 3}, {3, 11}, {11, 11}};
        for (int[] rivet : rivets) {
            image.setRGB(rivet[0], rivet[1], rgb(244, 208, 128));
            image.setRGB(rivet[0] + 1, rivet[1] + 1, rgb(120, 84, 34));
        }
        return image;
    }

    /** The lamp on top of the placed reader. Dark glass until it has a reading; then a soft amber, brightest in the middle. */
    static BufferedImage lamp(boolean lit) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double distance = Math.hypot(x + 0.5 - 8.0, y + 0.5 - 8.0);
                int grain = noise(x, y, 61) % 5 - 2;
                if (lit) {
                    double glow = Math.max(0.0, 1.0 - distance / 6.5);
                    image.setRGB(x, y, mix(rgb(214, 132, 52), rgb(255, 236, 170), glow * glow));
                } else {
                    image.setRGB(x, y, rgb(52 + grain, 46 + grain, 42 + grain));
                }
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

    private static int noise(int x, int y, int seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return (int) ((h ^ (h >>> 16)) & 0x7FFFFFFF);
    }

    private ReaderTextures() {
    }
}
