package dev.brights0ng.seismicsurveyance;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Seismic Surveyance: its id, logger and registration. Still part of the one engines_and_empires mod for now:
 * {@link #MODID} becomes "seismic_surveyance" when the mod is cut out (split step 2).
 */
public final class SeismicSurveyance {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    private SeismicSurveyance() {
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(SeismicSurveyance::commonSetup);

        // The seismic survey: the sledgehammer, strike plate, geophones, thumpers, readers and loggers
        SeismicContent.register(modEventBus);
        modEventBus.addListener(SeismicContent::registerCapabilities);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        // Tell Create how much stress the mechanical thumper puts on a network. Must run after the block is registered,
        // which is guaranteed by this event firing after the registry events that create it.
        event.enqueueWork(SeismicContent::registerStressValues);
    }
}
