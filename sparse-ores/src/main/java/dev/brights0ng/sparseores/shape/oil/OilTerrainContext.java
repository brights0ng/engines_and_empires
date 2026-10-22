// Written by Gemini.
package dev.brights0ng.sparseores.shape.oil;

/**
 * Environmental context for terrain-influenced oil deposit procedural generation.
 */
public interface OilTerrainContext {

    /** Biome identifier at the given column surface (e.g. "minecraft:desert"). */
    String biomeId(int x, int z);

    /** Whether the surface biome at (x, z) matches the given tag (e.g. "#minecraft:is_mountain"). */
    boolean hasBiomeTag(int x, int z, String tag);

    /** Surface terrain elevation (Y level) at (x, z). */
    int surfaceY(int x, int z);

    /** Regional ridge folding noise value at (x, z), or Double.NaN if unavailable. */
    double ridgeNoise(int x, int z);
}
