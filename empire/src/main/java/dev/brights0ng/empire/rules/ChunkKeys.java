// Written by Gemini.
package dev.brights0ng.empire.rules;

/**
 * Packs and unpacks 2D chunk coordinates to and from 64-bit longs, matching Minecraft's {@code ChunkPos.asLong(x, z)}
 * bit layout without importing Minecraft classes.
 */
public final class ChunkKeys {

    public static long pack(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static int getX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int getZ(long key) {
        return (int) ((key >>> 32) & 0xFFFFFFFFL);
    }

    private ChunkKeys() {
    }
}
