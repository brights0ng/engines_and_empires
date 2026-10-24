// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * A civilian's look: a style (1-5) and a colour (1-5) for each of the five skin categories. Rolled once at spawn, then
 * saved and synced as {@link #pack()}; never rerolled. {@link #code()} is the readable form the debug commands use.
 */
public record CivilianAppearance(int skinStyle, int skinColor, int hairStyle, int hairColor, int shirtStyle,
                                 int shirtColor, int pantsStyle, int pantsColor, int shoesStyle, int shoesColor) {

    public static final int SLOTS = 10;
    public static final int VARIANTS = 5;

    public enum Category {
        SKIN, HAIR, SHIRT, PANTS, SHOES;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public CivilianAppearance {
        int[] values = {skinStyle, skinColor, hairStyle, hairColor, shirtStyle, shirtColor, pantsStyle, pantsColor,
                shoesStyle, shoesColor};
        for (int v : values) {
            if (v < 1 || v > VARIANTS) {
                throw new IllegalArgumentException("appearance value out of 1.." + VARIANTS + ": " + v);
            }
        }
    }

    private static CivilianAppearance of(int[] v) {
        return new CivilianAppearance(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    }

    private int[] values() {
        return new int[]{skinStyle, skinColor, hairStyle, hairColor, shirtStyle, shirtColor, pantsStyle, pantsColor,
                shoesStyle, shoesColor};
    }

    /** Slot i holds (value - 1) at bits 3i..3i+2. */
    public int pack() {
        int[] v = values();
        int packed = 0;
        for (int i = 0; i < SLOTS; i++) {
            packed |= (v[i] - 1) << (3 * i);
        }
        return packed;
    }

    public static CivilianAppearance unpack(int packed) {
        int[] v = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            v[i] = ((packed >>> (3 * i)) & 0b111) + 1;
        }
        return of(v);
    }

    public static CivilianAppearance random(RandomGenerator random) {
        int[] v = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            v[i] = 1 + random.nextInt(VARIANTS);
        }
        return of(v);
    }

    /** Ten digits, one per value in declaration order, e.g. {@code "3245112354"}. */
    public String code() {
        StringBuilder sb = new StringBuilder(SLOTS);
        for (int v : values()) {
            sb.append((char) ('0' + v));
        }
        return sb.toString();
    }

    public static CivilianAppearance parse(String code) {
        if (code == null || code.length() != SLOTS) {
            throw new IllegalArgumentException("appearance code must be " + SLOTS + " digits 1-" + VARIANTS + ": " + code);
        }
        int[] v = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            int d = code.charAt(i) - '0';
            if (d < 1 || d > VARIANTS) {
                throw new IllegalArgumentException("appearance code must be " + SLOTS + " digits 1-" + VARIANTS + ": " + code);
            }
            v[i] = d;
        }
        return of(v);
    }

    public int style(Category category) {
        return values()[2 * category.ordinal()];
    }

    public int color(Category category) {
        return values()[2 * category.ordinal() + 1];
    }
}
