// Written by Claude.
package dev.brights0ng.empire.rules;

/**
 * An empire's divine area (Bright's design): the 3x3 chunks centred on its core chunk, the full height of the column.
 * Chunks are {@link ChunkKeys} longs.
 */
public final class DivineArea {

    /** How far, in chunks, the divine area reaches from the core chunk. */
    public static final int RADIUS = 1;

    /** Whether {@code chunk} is in the divine area of an empire whose core chunk is {@code core}. */
    public static boolean divine(long core, long chunk) {
        long dx = Math.abs((long) ChunkKeys.getX(chunk) - ChunkKeys.getX(core));
        long dz = Math.abs((long) ChunkKeys.getZ(chunk) - ChunkKeys.getZ(core));
        return Math.max(dx, dz) <= RADIUS;
    }

    /** Every chunk of the divine area around {@code core}, the core first. */
    public static long[] chunks(long core) {
        int cx = ChunkKeys.getX(core);
        int cz = ChunkKeys.getZ(core);
        int side = 2 * RADIUS + 1;
        long[] chunks = new long[side * side];
        chunks[0] = core;
        int i = 1;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx != 0 || dz != 0) {
                    chunks[i++] = ChunkKeys.pack(cx + dx, cz + dz);
                }
            }
        }
        return chunks;
    }

    private DivineArea() {
    }
}
