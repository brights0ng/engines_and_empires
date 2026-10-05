package dev.brights0ng.enginesandempires.oregen;

/**
 * One deposit location found on the ore map.
 *
 * @param oreId the {@link OreLayer#id()} it belongs to
 * @param x     block x of the deposit's centre
 * @param z     block z of the deposit's centre
 * @param seed  a seed unique to this deposit and world. Whatever decides the deposit's shape, size and
 *              density should draw its randomness from this, so the same deposit always comes out the
 *              same.
 */
public record Deposit(String oreId, int x, int z, long seed) {
}
