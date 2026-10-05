package dev.brights0ng.enginesandempires.frontier.tier;

/**
 * Packs a section's coordinates into a long, the same way Minecraft's {@code SectionPos.asLong} does (22 bits of x, 20 of
 * y, 22 of z), so keys can be passed between the two. Kept here so the tier logic can be tested without Minecraft.
 */
public final class SectionKey {

    public static long of(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | (y & 0xFFFFFL) | ((long) (z & 0x3FFFFF) << 20);
    }

    public static int x(long key) {
        return (int) (key >> 42);
    }

    public static int y(long key) {
        return (int) (key << 44 >> 44);
    }

    public static int z(long key) {
        return (int) (key << 22 >> 42);
    }

    /** How far apart two sections are, as a cube: the biggest difference along any axis. */
    public static int distance(long a, long b) {
        return Math.max(Math.abs(x(a) - x(b)), Math.max(Math.abs(y(a) - y(b)), Math.abs(z(a) - z(b))));
    }

    private SectionKey() {
    }
}
