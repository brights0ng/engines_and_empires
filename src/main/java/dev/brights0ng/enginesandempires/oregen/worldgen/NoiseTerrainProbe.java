package dev.brights0ng.enginesandempires.oregen.worldgen;

import dev.brights0ng.enginesandempires.oregen.TerrainProbe;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * A {@link TerrainProbe} that asks the world's own terrain noise.
 *
 * <p>The overworld and the Nether both build their terrain from a "final density" function of position:
 * where it is above zero the block is solid, otherwise it is air, water or lava. It depends only on the
 * world seed, so it can be evaluated at any position without generating or loading anything, which is
 * how the F3 debug screen shows it. That is what makes it possible to know, before a chunk exists,
 * whether a deposit's ore would be in rock.
 *
 * <p>It sees the base terrain only, as {@link TerrainProbe} explains, and evaluates the noise at the exact
 * block rather than interpolating between cell corners as chunk generation does, so it can differ from
 * the real terrain right at the edge of a surface. That does not matter for an estimate.
 *
 * <p>The density function holds no per-chunk state, so one instance can be used from any thread.
 */
final class NoiseTerrainProbe implements TerrainProbe {

    private final DensityFunction finalDensity;

    private NoiseTerrainProbe(DensityFunction finalDensity) {
        this.finalDensity = finalDensity;
    }

    /**
     * The probe for a level. Sky showcase mode builds deposits in open sky whatever the ground is, and a
     * dimension whose terrain is not noise-based (a flat or void world, say) cannot be predicted, so both
     * get a probe that accepts everything and rejects nothing.
     */
    static TerrainProbe forLevel(ServerLevel level) {
        if (OreWorldgen.SKY_SHOWCASE) {
            return TerrainProbe.ALL_SOLID;
        }
        ServerChunkCache chunks = level.getChunkSource();
        if (chunks.getGenerator() instanceof NoiseBasedChunkGenerator) {
            return new NoiseTerrainProbe(chunks.randomState().router().finalDensity());
        }
        return TerrainProbe.ALL_SOLID;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return finalDensity.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0;
    }
}
