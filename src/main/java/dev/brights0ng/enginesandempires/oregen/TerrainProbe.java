package dev.brights0ng.enginesandempires.oregen;

/**
 * Answers one question about terrain that has not been generated yet: would there be solid rock at this
 * block? The answer comes from the world's terrain noise, which is a pure function of the seed, so it
 * can be asked about any position without loading or generating anything, and always gives the same
 * answer.
 *
 * <p>It is an estimate of the base terrain only. It knows about open caverns, noise caves and the
 * huge open spaces of the Nether, but not about things added afterwards: carved caves and ravines, the
 * dirt and sand of the surface, structures, or lakes.
 *
 * <p>This is an interface so the logic that uses it stays free of Minecraft and can be tested against
 * made-up terrain. Implementations must be thread-safe.
 */
@FunctionalInterface
public interface TerrainProbe {

    /** Terrain that is solid everywhere. Used when nothing can be predicted, so nothing is ever rejected. */
    TerrainProbe ALL_SOLID = (x, y, z) -> true;

    /** Whether the block at this position would be solid rock. */
    boolean isSolid(int x, int y, int z);
}
