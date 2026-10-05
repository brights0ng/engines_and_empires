package dev.brights0ng.enginesandempires.oregen.worldgen;

import dev.brights0ng.enginesandempires.oregen.BiomeRule;
import dev.brights0ng.enginesandempires.oregen.OreType;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Finds the biome at the surface above any column, without generating or loading anything, and applies an
 * ore's {@link BiomeRule}s to it.
 *
 * <p>The surface height comes from the chunk generator's own base-height estimate and the biome from its
 * biome source and climate sampler. Both are pure functions of the world seed (they are what locate
 * structures and the "/locate biome" command), so every caller gets the same answer before or after the
 * chunk exists, and a deposit's size never depends on what happens to be loaded.
 *
 * <p>The surface is used, not the deposit's own depth, because underground the biome is often a cave biome
 * (dripstone caves lie under exactly the far-inland terrain mountains sit on), and because the surface is
 * what a player prospecting the landscape can see.
 *
 * <p>Holds no reference to the level, so it does not keep an unloaded level alive. Safe from any thread.
 */
final class SurfaceBiomes {

    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final LevelHeightAccessor heights;

    private SurfaceBiomes(ChunkGenerator generator, RandomState randomState, LevelHeightAccessor heights) {
        this.generator = generator;
        this.randomState = randomState;
        this.heights = heights;
    }

    static SurfaceBiomes forLevel(ServerLevel level) {
        return new SurfaceBiomes(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(),
                LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight()));
    }

    /** The height of the terrain surface (top of solid ground, ignoring water) in a column. */
    int surfaceY(int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, randomState);
    }

    /** The biome at the surface of a column. */
    Holder<Biome> biomeAt(int x, int z) {
        int y = surfaceY(x, z);
        return generator.getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), randomState.sampler());
    }

    /** The size factor an ore's shallow deposits get in this column. 1 for an ore without biome rules. */
    double factor(OreType type, int x, int z) {
        BiomeRule rule = rule(type, x, z);
        return rule == null ? 1.0 : rule.factor();
    }

    /** The biome rule an ore's deposits get in this column, or null for none. */
    BiomeRule rule(OreType type, int x, int z) {
        if (!type.hasBiomeRules()) {
            return null;
        }
        Holder<Biome> biome = biomeAt(x, z);
        return BiomeRule.select(type.biomes(), rule -> matches(biome, rule));
    }

    /** Whether a biome is picked out by a rule's selector. */
    static boolean matches(Holder<Biome> biome, BiomeRule rule) {
        ResourceLocation id = ResourceLocation.parse(rule.id());
        return rule.isTag() ? biome.is(TagKey.create(Registries.BIOME, id)) : biome.is(id);
    }
}
