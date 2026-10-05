package dev.brights0ng.enginesandempires.frontier.map;

/**
 * A square of chunk columns' tiers, packed two bits to a column (a tier's ordinal: 0 Frontier, 1 Uninhabited, 2 Settled,
 * 3 Civilized), row by row from the north-west corner, for sending to the maps.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class TierGrid {

    /** Packs {@code tiers} (each 0-3) four to a byte. */
    public static byte[] pack(byte[] tiers) {
        byte[] packed = new byte[(tiers.length + 3) / 4];
        for (int i = 0; i < tiers.length; i++) {
            packed[i >> 2] |= (byte) ((tiers[i] & 3) << ((i & 3) * 2));
        }
        return packed;
    }

    /** Unpacks {@code count} tiers from {@link #pack}'s bytes. */
    public static byte[] unpack(byte[] packed, int count) {
        byte[] tiers = new byte[count];
        for (int i = 0; i < count && (i >> 2) < packed.length; i++) {
            tiers[i] = (byte) ((packed[i >> 2] >> ((i & 3) * 2)) & 3);
        }
        return tiers;
    }

    /** Which sides of a column a tint's edge is drawn on: those whose neighbour is a lower tier. */
    public static final int NORTH = 1;
    public static final int SOUTH = 2;
    public static final int WEST = 4;
    public static final int EAST = 8;

    /** The sides of a column of tier {@code tier} whose neighbours (north, south, west, east) are of a lower tier. */
    public static int edges(int tier, int north, int south, int west, int east) {
        int edges = 0;
        if (north < tier) {
            edges |= NORTH;
        }
        if (south < tier) {
            edges |= SOUTH;
        }
        if (west < tier) {
            edges |= WEST;
        }
        if (east < tier) {
            edges |= EAST;
        }
        return edges;
    }

    private TierGrid() {
    }
}
