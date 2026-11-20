// Written by Claude (GreedyFluids M7d d1).
package dev.brights0ng.meteodyne.airflow;

/**
 * The fixed tile grid (Bright, 2026-11-15): tiles of {@link #TX}×{@link #TY}×{@link #TZ} blocks, aligned to multiples of
 * 64 horizontally and to bands of 32 counted from the world's bottom (the Overworld's −64, so bands sit on multiples
 * of 32 and a border falls at y = 64). Tiles never overlap; every block is in exactly one. Pure Java.
 */
public final class AirflowTiles {

    public static final int TX = 64, TY = 32, TZ = 64;

    /**
     * The origin (low corner, world blocks) of the tile holding block (x, y, z), in a world whose blocks run from
     * {@code minY} up to but not including {@code maxY}. Heights outside the world go to its bottom or top tile.
     */
    public static int[] origin(int minY, int maxY, int x, int y, int z) {
        if (maxY - minY < TY) {
            throw new IllegalArgumentException("the world is lower than one tile: " + minY + ".." + maxY);
        }
        int y0 = minY + TY * Math.floorDiv(y - minY, TY);
        y0 = Math.max(minY, Math.min(y0, maxY - TY));
        return new int[] {TX * Math.floorDiv(x, TX), y0, TZ * Math.floorDiv(z, TZ)};
    }

    /** As {@link #origin(int, int, int, int, int)} for a point, world blocks. */
    public static int[] origin(int minY, int maxY, double x, double y, double z) {
        return origin(minY, maxY, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /** A map key for the tile with this origin (unique for origins inside a world's horizontal limits). */
    public static long key(int x0, int y0, int z0) {
        // 26 bits each for x and z tile indices, 12 for the band: Minecraft's ±30,000,000 blocks fit with room to spare
        long ix = Math.floorDiv(x0, TX) & 0x3FFFFFFL, iz = Math.floorDiv(z0, TZ) & 0x3FFFFFFL;
        long iy = Math.floorDiv(y0, TY) & 0xFFFL;
        return ix << 38 | iz << 12 | iy;
    }

    private AirflowTiles() {
    }
}
