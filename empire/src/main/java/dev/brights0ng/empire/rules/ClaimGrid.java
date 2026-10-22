// Written by Gemini.
package dev.brights0ng.empire.rules;

/**
 * A square of chunk columns' claim states, packed two bits to a column (0 Unclaimed, 1 Our Empire, 2 Other Empire),
 * row by row from the north-west corner, for sending to maps.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class ClaimGrid {

    public static final int UNCLAIMED = 0;
    public static final int OUR_EMPIRE = 1;
    public static final int OTHER_EMPIRE = 2;

    /** Packs {@code states} (each 0-3) four to a byte. */
    public static byte[] pack(byte[] states) {
        byte[] packed = new byte[(states.length + 3) / 4];
        for (int i = 0; i < states.length; i++) {
            packed[i >> 2] |= (byte) ((states[i] & 3) << ((i & 3) * 2));
        }
        return packed;
    }

    /** Unpacks {@code count} states from {@link #pack}'s bytes. */
    public static byte[] unpack(byte[] packed, int count) {
        byte[] states = new byte[count];
        for (int i = 0; i < count && (i >> 2) < packed.length; i++) {
            states[i] = (byte) ((packed[i >> 2] >> ((i & 3) * 2)) & 3);
        }
        return states;
    }

    /** Which sides of a column a tint's edge is drawn on: those whose neighbour is not in the same state. */
    public static final int NORTH = 1;
    public static final int SOUTH = 2;
    public static final int WEST = 4;
    public static final int EAST = 8;

    /**
     * The sides of a column of state {@code state} whose neighbours (north, south, west, east) do not match this state.
     * Unclaimed columns have no edges.
     */
    public static int edges(int state, int north, int south, int west, int east) {
        if (state == UNCLAIMED) {
            return 0;
        }
        int edges = 0;
        if (north != state) {
            edges |= NORTH;
        }
        if (south != state) {
            edges |= SOUTH;
        }
        if (west != state) {
            edges |= WEST;
        }
        if (east != state) {
            edges |= EAST;
        }
        return edges;
    }

    private ClaimGrid() {
    }
}
