package dev.brights0ng.simplespoilage;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.enginesandempires.food.FoodContent;
import dev.brights0ng.enginesandempires.food.SpoilageConfig;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

/**
 * Simple Spoilage (food spoilage): its id, logger and registration. Still part of the one engines_and_empires mod for
 * now: {@link #MODID} becomes "simple_spoilage" when the mod is cut out (split step 2).
 */
public final class SimpleSpoilage {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    private SimpleSpoilage() {
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        // Food spoilage: the freshness component every food stack carries, and its stage lengths (a SERVER config of its own)
        FoodContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, SpoilageConfig.SPEC, SpoilageConfig.FILE_NAME);
    }
}
