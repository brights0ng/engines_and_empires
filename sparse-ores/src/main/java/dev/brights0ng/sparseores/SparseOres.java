package dev.brights0ng.sparseores;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.sparseores.chips.ChipDataGen;
import dev.brights0ng.sparseores.chips.ChipItems;
import dev.brights0ng.sparseores.rich.RichOreBlocks;
import dev.brights0ng.sparseores.worldgen.OreWorldgen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Sparse Ores (ore generation): its id, logger and registration. A mod of its own since split step 2e; it requires
 * Paradyne Lib, and Create is optional (with it, zinc deposits are Create's zinc ore, and there is rich zinc ore).
 */
@Mod(SparseOres.MODID)
public final class SparseOres {

    public static final String MODID = "sparse_ores";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SparseOres(IEventBus modEventBus, ModContainer modContainer) {
        init(modEventBus, modContainer);
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void init(IEventBus modEventBus, ModContainer modContainer) {
        // The ore worldgen feature type (the data files under data/sparse_ores/ put it to use)
        OreWorldgen.register(modEventBus);

        // The rich ore blocks and items. Their loot, models and tags come from RichOreDataGen's parts, which
        // SparseOresDataGen puts together with the chips' models
        RichOreBlocks.register(modEventBus);

        // The chips (coal, diamond, quartz): what those ores drop instead of a whole item, and their recipes, fuel value
        // and textures
        ChipItems.register(modEventBus);
        modEventBus.addListener(ChipDataGen::gatherData);
        modEventBus.addListener(SparseOresDataGen::gatherData);
    }
}
