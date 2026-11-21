// Written by Gemini.
package dev.brights0ng.empire.rules;

/**
 * Packs and unpacks 3D block coordinates to and from 64-bit longs, matching Minecraft's
 * {@code BlockPos.asLong(x, y, z)} bit layout (26-bit signed X, 26-bit signed Z, 12-bit signed Y)
 * without importing Minecraft classes.
 */
public final class BlockKeys {

    private static final int X_BITS = 26;
    private static final int Z_BITS = 26;
    private static final int Y_BITS = 12;

    private static final int Z_OFFSET = Y_BITS;
    private static final int X_OFFSET = Y_BITS + Z_BITS;

    private static final long X_MASK = (1L << X_BITS) - 1L;
    private static final long Z_MASK = (1L << Z_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;

    public static long pack(int x, int y, int z) {
        return (((long) x & X_MASK) << X_OFFSET)
                | (((long) z & Z_MASK) << Z_OFFSET)
                | ((long) y & Y_MASK);
    }

    public static int getX(long key) {
        return (int) (key >> X_OFFSET);
    }

    public static int getY(long key) {
        return (int) (key << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int getZ(long key) {
        return (int) (key << X_BITS >> (X_BITS + Z_OFFSET));
    }

    /** Packs the 2D chunk coordinate containing {@code blockKey}. */
    public static long chunkKey(long blockKey) {
        return ChunkKeys.pack(getX(blockKey) >> 4, getZ(blockKey) >> 4);
    }

    private BlockKeys() {
    }
}
