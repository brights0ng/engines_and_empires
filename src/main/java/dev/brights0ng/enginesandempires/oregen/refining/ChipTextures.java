package dev.brights0ng.enginesandempires.oregen.refining;

import java.awt.image.BufferedImage;

/**
 * Draws a chip from the whole item's own texture, so it keeps vanilla's colours and shading: a small broken-off shard
 * of the item, with a darker rim. These are stand-ins in the same spirit as the script-made rich ore textures; an
 * artist can replace them without touching code.
 */
public final class ChipTextures {

    /** The shard, 8 wide and 7 tall, placed at {@link #LEFT}, {@link #TOP}. '#' is the shard, '.' is empty. */
    static final String[] SHAPE = {
            "...##...",
            "..####..",
            ".######.",
            "#######.",
            ".######.",
            "..####..",
            "...#....",
    };
    static final int LEFT = 4;
    static final int TOP = 5;

    /** How much the rim is darkened: its colour is multiplied by this. */
    static final double RIM_SHADE = 0.6;

    /**
     * Cuts a chip out of a 16x16 item texture. Each shard pixel takes the item's pixel at the same place; where the item
     * is transparent there, it takes the item's typical colour instead. Shard pixels on its edge are darkened.
     */
    public static BufferedImage chip(BufferedImage item) {
        if (item.getWidth() != 16 || item.getHeight() != 16) {
            throw new IllegalArgumentException("expected a 16x16 item texture, got " + item.getWidth() + "x" + item.getHeight());
        }
        int fill = typicalColour(item);
        BufferedImage chip = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SHAPE.length; y++) {
            for (int x = 0; x < SHAPE[y].length(); x++) {
                if (!inShape(x, y)) {
                    continue;
                }
                int px = LEFT + x;
                int py = TOP + y;
                int argb = item.getRGB(px, py);
                if ((argb >>> 24) < 128) {
                    argb = fill;
                }
                argb |= 0xFF000000;
                if (onRim(x, y)) {
                    argb = shade(argb, RIM_SHADE);
                }
                chip.setRGB(px, py, argb);
            }
        }
        return chip;
    }

    static boolean inShape(int x, int y) {
        return y >= 0 && y < SHAPE.length && x >= 0 && x < SHAPE[y].length() && SHAPE[y].charAt(x) == '#';
    }

    /** A shard pixel with an empty neighbour above, below, left or right. */
    static boolean onRim(int x, int y) {
        return !inShape(x - 1, y) || !inShape(x + 1, y) || !inShape(x, y - 1) || !inShape(x, y + 1);
    }

    /** The average of the item's opaque pixels. */
    static int typicalColour(BufferedImage item) {
        long r = 0;
        long g = 0;
        long b = 0;
        int n = 0;
        for (int y = 0; y < item.getHeight(); y++) {
            for (int x = 0; x < item.getWidth(); x++) {
                int argb = item.getRGB(x, y);
                if ((argb >>> 24) >= 128) {
                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += argb & 0xFF;
                    n++;
                }
            }
        }
        if (n == 0) {
            return 0xFF808080;
        }
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    static int shade(int argb, double factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private ChipTextures() {
    }
}
