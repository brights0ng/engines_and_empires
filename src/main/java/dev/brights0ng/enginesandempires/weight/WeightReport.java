package dev.brights0ng.enginesandempires.weight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;

/**
 * After the server (re)loads its data, lists in the log every item that got the {@link ItemWeights#FALLBACK} weight
 * because no group or rule covers it, grouped by mod. Those are the items to sort into a weight group.
 */
public final class WeightReport {

    public static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        if (event.getCause() != DataMapsUpdatedEvent.UpdateCause.SERVER_RELOAD) {
            return;
        }
        event.ifRegistry(Registries.ITEM, registry -> {
            Map<String, List<String>> byMod = new TreeMap<>();
            int total = 0;
            for (Holder<Item> holder : registry.asHolderIdMap()) {
                if (holder.value() == Items.AIR || ItemWeights.resolve(holder).source() != ItemWeights.Source.FALLBACK) {
                    continue;
                }
                ResourceLocation id = holder.unwrapKey().orElseThrow().location();
                byMod.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>()).add(id.getPath());
                total++;
            }
            EnginesAndEmpiresMod.LOGGER.info("Item weights: {} items have no weight group and weigh the fallback {} kpg", total,
                    ItemWeights.format(ItemWeights.FALLBACK));
            byMod.forEach((mod, items) -> EnginesAndEmpiresMod.LOGGER.info("  {} ({}): {}", mod, items.size(), String.join(", ", items)));
        });
    }

    private WeightReport() {
    }
}
