package dev.brights0ng.enginesandempires.weather.climate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * The biome climate data map, {@code data/engines_and_empires/data_maps/worldgen/biome/biome_climate.json}: each
 * biome's {@link BiomeClimate}. Entries can name single biomes or biome tags (a datapack can add modded biomes either
 * way); biomes left out get {@link BiomeClimate#fallback}. Server side only (clients get temperatures from the weather
 * sync), so it isn't synced.
 */
public final class ClimateDataMaps {

    public static final Codec<BiomeClimate.Surface> SURFACE_CODEC = Codec.STRING.comapFlatMap(name -> {
        BiomeClimate.Surface s = BiomeClimate.Surface.byName(name);
        return s == null ? DataResult.error(() -> "Unknown surface: " + name) : DataResult.success(s);
    }, BiomeClimate.Surface::getSerializedName);

    public static final Codec<BiomeClimate> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.doubleRange(-60, 60).fieldOf("mean").forGetter(BiomeClimate::mean),
            Codec.doubleRange(0, 40).fieldOf("seasonal_swing").forGetter(BiomeClimate::swing),
            Codec.doubleRange(0, 1).fieldOf("humidity").forGetter(BiomeClimate::humidity),
            SURFACE_CODEC.optionalFieldOf("surface", BiomeClimate.Surface.LAND).forGetter(BiomeClimate::surface),
            Codec.BOOL.optionalFieldOf("frozen", false).forGetter(BiomeClimate::frozen)
    ).apply(i, BiomeClimate::new));

    public static final DataMapType<Biome, BiomeClimate> BIOME_CLIMATE = DataMapType.builder(
                    ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "biome_climate"),
                    Registries.BIOME, CODEC)
            .build();

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(BIOME_CLIMATE);
    }

    private ClimateDataMaps() {
    }
}
