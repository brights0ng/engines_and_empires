package dev.brights0ng.sparseores;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.sparseores.chips.ChipDataGen;
import dev.brights0ng.sparseores.rich.RichOreDataGen;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Sparse Ores' data generator, beyond the chips' recipes and textures ({@link ChipDataGen#gatherData}): the providers a
 * run may have only one of (one loot table provider, as its name is fixed; one block state and one block tag provider,
 * as two would write the same files), put together from the rich ores' parts ({@link RichOreDataGen}) and the chips'
 * models and ordinary ores' loot, and the empty structure its game tests build on. Until split step 2e the core's {@code SharedDataGen} put
 * these parts together with the other mods' in the core's one output.
 *
 * <p>Run Sparse Ores' {@code data} run ({@code ./gradlew :sparse-ores:runData}) to (re)generate everything into
 * {@code sparse-ores/src/generated/resources}. It loads Create, or rich zinc ore would not exist.
 */
public final class SparseOresDataGen {

    /** Registered on the mod event bus by the mod class. */
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existing = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        generator.addProvider(event.includeServer(), new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(RichOreDataGen.Loot::new, LootContextParamSets.BLOCK),
                        new LootTableProvider.SubProviderEntry(ChipDataGen.OrdinaryLoot::new, LootContextParamSets.BLOCK)),
                lookup));
        // Rich zinc ore's tables, with a condition on Create (a provider of its own: the one above writes no conditions)
        generator.addProvider(event.includeServer(), new RichOreDataGen.OtherModsLoot(output, lookup));
        generator.addProvider(event.includeClient(), new States(output, existing));
        BlockTags blockTags = new BlockTags(output, lookup, existing);
        generator.addProvider(event.includeServer(), blockTags);
        generator.addProvider(event.includeServer(),
                new RichOreDataGen.ItemTagsGen(output, lookup, blockTags.contentsGetter(), existing));
        generator.addProvider(event.includeServer(), new GameTestStructures(output, SparseOres.MODID));
    }

    /** Block states, block models and item models: the rich ores' and the chips'. */
    private static final class States extends BlockStateProvider {

        States(PackOutput output, ExistingFileHelper existing) {
            super(output, SparseOres.MODID, existing);
        }

        @Override
        protected void registerStatesAndModels() {
            RichOreDataGen.registerModels(this);
            ChipDataGen.registerModels(this);
        }
    }

    /** Block tags: the rich ores' mining and ore tags. */
    private static final class BlockTags extends BlockTagsProvider {

        BlockTags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper existing) {
            super(output, lookup, SparseOres.MODID, existing);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            RichOreDataGen.addBlockTags(this::tag);
        }
    }

    private SparseOresDataGen() {
    }
}
