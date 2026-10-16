package dev.brights0ng.paradynelib;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.enginesandempires.weather.wind.WindConfig;
import dev.brights0ng.enginesandempires.weather.wind.WindContent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

/**
 * Paradyne Lib: its id, logger and registration. Still part of the one engines_and_empires mod for now: {@link #MODID}
 * becomes "paradyne_lib" when the library is cut out into a mod of its own (split step 2).
 */
public final class ParadyneLib {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    private ParadyneLib() {
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        // Wind: the weather's wind pushes Sable's physics objects (its settings are a SERVER config of their own)
        WindContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, WindConfig.SPEC, WindConfig.FILE_NAME);
    }
}
