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
 * biome's {@link BiomeAdjust}: how it adjusts the climate the world's noise gives ({@link NoiseClimate}; since
 * 2026-10-10 the climate itself no longer comes from a per-biome table). All fields are optional:
 * {@code temperature_offset} (C), {@code humidity_offset}, {@code humidity} (0-1, replaces the noise's),
 * {@code surface} (land, forest, water, ice; worked out from the biome's tags if left out) and {@code frozen}.
 * Entries can name single biomes or biome tags (a datapack can add modded biomes either way); biomes left out get no
 * adjustment, their surface from their tags, and never thaw if tagged icy ({@code c:is_icy}). Server side only (clients
 * get temperatures from the weather sync), so it isn't synced.
 */
public final class ClimateDataMaps {

    public static final Codec<BiomeClimate.Surface> SURFACE_CODEC = Codec.STRING.comapFlatMap(name -> {
        BiomeClimate.Surface s = BiomeClimate.Surface.byName(name);
        return s == null ? DataResult.error(() -> "Unknown surface: " + name) : DataResult.success(s);
    }, BiomeClimate.Surface::getSerializedName);

    public static final Codec<BiomeAdjust> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.doubleRange(-40, 40).optionalFieldOf("temperature_offset", 0.0)
                    .forGetter(BiomeAdjust::temperatureOffset),
            Codec.doubleRange(-1, 1).optionalFieldOf("humidity_offset", 0.0).forGetter(BiomeAdjust::humidityOffset),
            Codec.doubleRange(0, 1).optionalFieldOf("humidity")
                    .forGetter(a -> a.overridesHumidity() ? java.util.Optional.of(a.humidity()) : java.util.Optional.empty()),
            SURFACE_CODEC.optionalFieldOf("surface").forGetter(a -> java.util.Optional.ofNullable(a.surface())),
            Codec.BOOL.optionalFieldOf("frozen", false).forGetter(BiomeAdjust::frozen)
    ).apply(i, (t, dh, h, surface, frozen) -> new BiomeAdjust(t, dh, h.orElse(Double.NaN), surface.orElse(null),
            frozen)));

    public static final DataMapType<Biome, BiomeAdjust> BIOME_CLIMATE = DataMapType.builder(
                    ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "biome_climate"),
                    Registries.BIOME, CODEC)
            .build();

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(BIOME_CLIMATE);
    }

    private ClimateDataMaps() {
    }
}
