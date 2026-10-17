package dev.brights0ng.simplespoilage;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.simplespoilage.sable.SpoilageSable;
import net.minecraft.data.DataGenerator;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Simple Spoilage (food spoilage): its id, logger and registration. A mod of its own since split step 2d; it requires
 * Paradyne Lib, and Sable is optional.
 */
@Mod(SimpleSpoilage.MODID)
public final class SimpleSpoilage {

    public static final String MODID = "simple_spoilage";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SimpleSpoilage(IEventBus modEventBus, ModContainer modContainer) {
        init(modEventBus, modContainer);
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void init(IEventBus modEventBus, ModContainer modContainer) {
        // Food spoilage: the freshness component every food stack carries, and its stage lengths (a SERVER config of its own)
        FoodContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, SpoilageConfig.SPEC, SpoilageConfig.FILE_NAME);
        // Ships' holds (Sable sub-levels) age their food like any other place. Sable is optional: everything that touches
        // it is in the sable sub-package, reached only from here, when it is loaded
        if (ModList.get().isLoaded("sable")) {
            SpoilageSable.register();
        }
        modEventBus.addListener(SimpleSpoilage::gatherData);
    }

    /** Simple Spoilage's data beyond the ice chest's textures: the empty structure its game tests build on. */
    private static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(event.includeServer(), new GameTestStructures(generator.getPackOutput(), MODID));
    }
}
