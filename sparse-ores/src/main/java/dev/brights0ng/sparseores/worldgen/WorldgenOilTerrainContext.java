// Written by Gemini.
package dev.brights0ng.sparseores.worldgen;

import dev.brights0ng.sparseores.shape.oil.OilTerrainContext;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Concrete {@link OilTerrainContext} backed by Minecraft world generation sources.
 */
public final class WorldgenOilTerrainContext implements OilTerrainContext {

    private final SurfaceBiomes surfaceBiomes;
    private final DensityFunction ridges;

    public WorldgenOilTerrainContext(ServerLevel level) {
        this.surfaceBiomes = SurfaceBiomes.forLevel(level);
        RandomState rs = level.getChunkSource().randomState();
        this.ridges = rs != null && rs.router() != null ? rs.router().ridges() : null;
    }

    @Override
    public String biomeId(int x, int z) {
        Holder<Biome> biome = surfaceBiomes.biomeAt(x, z);
        return biome.unwrapKey().map(k -> k.location().toString()).orElse("");
    }

    @Override
    public boolean hasBiomeTag(int x, int z, String tag) {
        Holder<Biome> biome = surfaceBiomes.biomeAt(x, z);
        String tagId = tag.startsWith("#") ? tag.substring(1) : tag;
        return biome.is(TagKey.create(Registries.BIOME, ResourceLocation.parse(tagId)));
    }

    @Override
    public int surfaceY(int x, int z) {
        return surfaceBiomes.surfaceY(x, z);
    }

    @Override
    public double ridgeNoise(int x, int z) {
        if (ridges == null) {
            return Double.NaN;
        }
        return ridges.compute(new DensityFunction.SinglePointContext(x, 0, z));
    }
}
