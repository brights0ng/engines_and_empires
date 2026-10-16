package dev.brights0ng.enginesandempires.oregen.refining;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import com.google.gson.JsonObject;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableSubProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * The data generator's providers for ore refining: the recipes of {@link RefiningRecipes} and the iron golem's cut-down
 * drop. Ore block loot tables are generated with the rich ones, in {@code RichOreDataGen}, from the same {@code RichOres}
 * table; the chips' own recipes, models and textures are Sparse Ores' ({@code sparseores/chips/ChipDataGen}). The
 * recipes are added by {@link #gatherData}; the golem's loot goes into the shared loot provider
 * ({@code datagen/SharedDataGen}).
 */
public final class RefiningDataGen {

    /** Registered on the mod event bus by the mod class. */
    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeServer(), new Recipes(event.getGenerator().getPackOutput()));
    }

    /** Writes {@link RefiningRecipes#files()}. */
    public static final class Recipes implements DataProvider {

        private final PackOutput output;

        public Recipes(PackOutput output) {
            this.output = output;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            Path root = output.getOutputFolder(PackOutput.Target.DATA_PACK);
            List<CompletableFuture<?>> writes = new ArrayList<>();
            for (Map.Entry<String, JsonObject> file : RefiningRecipes.files().entrySet()) {
                writes.add(DataProvider.saveStable(cache, file.getValue(), root.resolve(file.getKey())));
            }
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Ore refining recipes";
        }
    }

    /** The iron golem drops nuggets, cut like raw ore: 3-5 ingots become 6-10 nuggets. Its poppies are kept. */
    public static final class GolemLoot implements LootTableSubProvider {

        public GolemLoot(HolderLookup.Provider registries) {
        }

        @Override
        public void generate(BiConsumer<ResourceKey<LootTable>, LootTable.Builder> output) {
            int min = (int) Math.round(3 * 9 * Refining.OTHER_SOURCES);
            int max = (int) Math.round(5 * 9 * Refining.OTHER_SOURCES);
            output.accept(EntityType.IRON_GOLEM.getDefaultLootTable(), LootTable.lootTable()
                    .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                            .add(LootItem.lootTableItem(Items.POPPY)
                                    .apply(SetItemCountFunction.setCount(UniformGenerator.between(0, 2)))))
                    .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                            .add(LootItem.lootTableItem(Items.IRON_NUGGET)
                                    .apply(SetItemCountFunction.setCount(UniformGenerator.between(min, max))))));
        }
    }

    private RefiningDataGen() {
    }
}
