package dev.brights0ng.enginesandempires.datagen;

import java.util.List;
import java.util.Set;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.enginesandempires.oregen.refining.RefiningDataGen;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * The data generator's shared providers (split seam S9). Until the split's step 2 gave each mod its own output, every
 * mod wrote into this jar's one namespace and folder, so a run could have only one loot table provider (its name is
 * fixed) and one block state and one block tag provider (two would write the same {@code mineable/pickaxe} tag), and
 * the core put them together here from each mod's parts. Sparse Ores and Seismic Surveyance have their own output since
 * split step 2e and put their parts together themselves ({@code sparseores/SparseOresDataGen},
 * {@code seismicsurveyance/SeismicDataGen}), so what is left is the core's: the loot table provider, for the iron golem
 * ({@link RefiningDataGen}), and the empty game test structure in this jar's namespace. This class goes in the last
 * slicing step (split step 2f).
 *
 * <p>Run the {@code Data} run configuration to (re)generate everything into {@code src/generated/resources}.
 */
public final class SharedDataGen {

    /** Registered on the mod event bus by the mod class. */
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();

        generator.addProvider(event.includeServer(), new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(RefiningDataGen.GolemLoot::new, LootContextParamSets.ENTITY)),
                event.getLookupProvider()));
        generator.addProvider(event.includeServer(), new GameTestStructures(output, EnginesAndEmpires.MODID));
    }

    private SharedDataGen() {
    }
}
