package dev.brights0ng.enginesandempires.datagen;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.enginesandempires.oregen.refining.RefiningDataGen;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.seismicsurveyance.SeismicDataGen;
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
 * The data generator's shared providers (split seam S9). Until the split's step 2 gives each mod its own output, every
 * mod writes into this jar's one namespace and folder, so a run can have only one loot table provider (its name is
 * fixed) and one block state and one block tag provider (two would write the same {@code mineable/pickaxe} tag). The
 * core, which may see every mod, puts them together here from each mod's parts: Sparse Ores' ({@link RichOreDataGen},
 * {@link ChipDataGen}), Seismic Surveyance's ({@link SeismicDataGen}) and its own ({@link RefiningDataGen}). Each mod adds its other providers
 * from its own data generator. The empty game test structure, in this jar's namespace, is
 * written here too (the library writes its own, in its own data run).
 *
 * <p>Run the {@code Data} run configuration to (re)generate everything into {@code src/generated/resources}.
 */
public final class SharedDataGen {

    /** Registered on the mod event bus by the mod class. */
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existing = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        generator.addProvider(event.includeServer(), new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(RichOreDataGen.Loot::new, LootContextParamSets.BLOCK),
                        new LootTableProvider.SubProviderEntry(SeismicDataGen.Loot::new, LootContextParamSets.BLOCK),
                        new LootTableProvider.SubProviderEntry(RefiningDataGen.GolemLoot::new, LootContextParamSets.ENTITY)),
                lookup));
        generator.addProvider(event.includeClient(), new States(output, existing));
        BlockTags blockTags = new BlockTags(output, lookup, existing);
        generator.addProvider(event.includeServer(), blockTags);
        generator.addProvider(event.includeServer(),
                new RichOreDataGen.ItemTagsGen(output, lookup, blockTags.contentsGetter(), existing));
        generator.addProvider(event.includeServer(), new GameTestStructures(output, EnginesAndEmpires.MODID));
    }

    /** Block states, block models and item models. */
    private static final class States extends BlockStateProvider {

        States(PackOutput output, ExistingFileHelper existing) {
            super(output, EnginesAndEmpires.MODID, existing);
        }

        @Override
        protected void registerStatesAndModels() {
            RichOreDataGen.registerModels(this);
            SeismicDataGen.registerModels(this);
            ChipDataGen.registerModels(this);
        }
    }

    /** Block tags. */
    private static final class BlockTags extends BlockTagsProvider {

        BlockTags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper existing) {
            super(output, lookup, EnginesAndEmpires.MODID, existing);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            RichOreDataGen.addBlockTags(this::tag);
            SeismicDataGen.addBlockTags(this::tag);
        }
    }

    private SharedDataGen() {
    }
}
