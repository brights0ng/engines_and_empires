package dev.brights0ng.seismicsurveyance;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Seismic Surveyance: its id, logger and registration. A mod of its own since split step 2e; it requires Paradyne Lib,
 * Sparse Ores and Create, and Create: Diesel Generators is optional (its fuels are found by fluid tag).
 */
@Mod(SeismicSurveyance.MODID)
public final class SeismicSurveyance {

    public static final String MODID = "seismic_surveyance";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SeismicSurveyance(IEventBus modEventBus, ModContainer modContainer) {
        init(modEventBus, modContainer);
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void init(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(SeismicSurveyance::commonSetup);

        // The seismic survey: the sledgehammer, strike plate, geophones, thumpers, readers and loggers
        SeismicContent.register(modEventBus);
        modEventBus.addListener(SeismicContent::registerCapabilities);
        // Its data generator (its data run)
        modEventBus.addListener(SeismicDataGen::gatherData);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        // Tell Create how much stress the mechanical thumper puts on a network. Must run after the block is registered,
        // which is guaranteed by this event firing after the registry events that create it.
        event.enqueueWork(SeismicContent::registerStressValues);
    }
}
