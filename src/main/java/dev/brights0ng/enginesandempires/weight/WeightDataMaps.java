package dev.brights0ng.enginesandempires.weight;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * The pack's item weight data map, {@code data/engines_and_empires/data_maps/item/item_weight.json}.
 *
 * <p>It holds the weight groups (by item tag, see {@code tags/item/weight/}) and any single-item exceptions. It is
 * synced to clients so the weight tooltip is right. Encumbered's own {@code encumbered:item_weights} map is ignored:
 * {@link ItemWeights} answers every lookup instead.
 */
public final class WeightDataMaps {

    public static final DataMapType<Item, ItemWeight> ITEM_WEIGHT = DataMapType.builder(
                    ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "item_weight"),
                    Registries.ITEM, ItemWeight.CODEC)
            .synced(ItemWeight.CODEC, false)
            .build();

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(ITEM_WEIGHT);
    }

    private WeightDataMaps() {
    }
}
