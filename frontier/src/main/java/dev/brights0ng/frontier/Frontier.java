package dev.brights0ng.frontier;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.frontier.FrontierConfig;
import dev.brights0ng.frontier.FrontierContent;
import dev.brights0ng.frontier.seismic.FrontierSeismic;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.data.DataGenerator;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Frontier: its id, logger and registration. A mod of its own since split step 2d; it requires Paradyne Lib, and
 * Seismic Surveyance is optional (reached only through the {@code seismic} package).
 */
@Mod(Frontier.MODID)
public final class Frontier {

    public static final String MODID = "frontier";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Frontier(IEventBus modEventBus, ModContainer modContainer) {
        init(modEventBus, modContainer);
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void init(IEventBus modEventBus, ModContainer modContainer) {
        // Frontier: how civilised each part of the world is. Its settings are per world (a SERVER config).
        FrontierContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, FrontierConfig.SPEC);
        // Seismic Surveyance's thumpers: what their shots set off below (only if Seismic Surveyance is installed)
        FrontierSeismic.registerIfLoaded(modEventBus);
        modEventBus.addListener(Frontier::gatherData);
    }

    /** Frontier's data beyond its textures ({@code FrontierDataGen}): the empty structure its game tests build on. */
    private static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(event.includeServer(), new GameTestStructures(generator.getPackOutput(), MODID));
    }
}
