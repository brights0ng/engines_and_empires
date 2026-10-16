package dev.brights0ng.paradynelib;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.sable.wind.WindContent;
import dev.brights0ng.paradynelib.wind.WindConfig;
import net.minecraft.data.DataGenerator;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Paradyne Lib: its id, logger and registration. A mod of its own since split step 2b; the other mods of the family
 * require it.
 */
@Mod(ParadyneLib.MODID)
public final class ParadyneLib {

    public static final String MODID = "paradyne_lib";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ParadyneLib(IEventBus modEventBus, ModContainer modContainer) {
        init(modEventBus, modContainer);
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void init(IEventBus modEventBus, ModContainer modContainer) {
        // Wind: the weather's wind pushes Sable's physics objects (its settings are a SERVER config of their own). Sable
        // is optional: everything that touches it is in the sable sub-package, reached only from here, when it is loaded.
        if (ModList.get().isLoaded("sable")) {
            WindContent.register(modEventBus);
        }
        modContainer.registerConfig(ModConfig.Type.SERVER, WindConfig.SPEC, WindConfig.FILE_NAME);
        modEventBus.addListener(ParadyneLib::gatherData);
    }

    /** The library's data: the empty structure its own game tests build on. */
    private static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(event.includeServer(), new GameTestStructures(generator.getPackOutput(), MODID));
    }
}
