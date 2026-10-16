package dev.brights0ng.frontier;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.frontier.FrontierConfig;
import dev.brights0ng.frontier.FrontierContent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

/**
 * Frontier: its id, logger and registration. Still part of the one engines_and_empires mod for now: {@link #MODID}
 * becomes "frontier" when the mod is cut out (split step 2).
 */
public final class Frontier {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    private Frontier() {
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        // Frontier: how civilised each part of the world is. Its settings are per world (a SERVER config).
        FrontierContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, FrontierConfig.SPEC);
    }
}
