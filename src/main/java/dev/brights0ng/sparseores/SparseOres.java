package dev.brights0ng.sparseores;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.sparseores.rich.RichOreBlocks;
import dev.brights0ng.sparseores.rich.RichOreDataGen;
import dev.brights0ng.sparseores.worldgen.OreWorldgen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/**
 * Sparse Ores (ore generation): its id, logger and registration. Still part of the one engines_and_empires mod for now:
 * {@link #MODID} becomes "sparse_ores" when the mod is cut out (split step 2).
 */
public final class SparseOres {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    private SparseOres() {
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        // The ore worldgen feature type (the data files under data/engines_and_empires/ put it to use)
        OreWorldgen.register(modEventBus);

        // The rich ore blocks and items, and the data generator that makes their loot, models and tags
        RichOreBlocks.register(modEventBus);
        modEventBus.addListener(RichOreDataGen::gatherData);
    }
}
