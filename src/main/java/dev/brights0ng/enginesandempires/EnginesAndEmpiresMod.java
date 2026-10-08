package dev.brights0ng.enginesandempires;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.FrontierContent;
import dev.brights0ng.enginesandempires.food.FoodContent;
import dev.brights0ng.enginesandempires.food.SpoilageConfig;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import dev.brights0ng.enginesandempires.oregen.refining.RefiningItems;
import dev.brights0ng.enginesandempires.oregen.rich.RichOreBlocks;
import dev.brights0ng.enginesandempires.oregen.rich.RichOreDataGen;
import dev.brights0ng.enginesandempires.oregen.worldgen.OreWorldgen;
import dev.brights0ng.enginesandempires.weight.WeightDataMaps;
import dev.brights0ng.enginesandempires.weight.WeightReport;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(EnginesAndEmpiresMod.MODID)
public class EnginesAndEmpiresMod {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EnginesAndEmpiresMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // The ore worldgen feature type (the data files under data/engines_and_empires/ put it to use)
        OreWorldgen.register(modEventBus);

        // The rich ore blocks and items, and the data generator that makes their loot, models and tags
        RichOreBlocks.register(modEventBus);

        // The chips (coal, diamond, quartz): what those ores drop now instead of a whole item
        RefiningItems.register(modEventBus);

        // The seismic survey: the sledgehammer, strike plate, geophones, thumpers, readers and loggers
        SeismicContent.register(modEventBus);
        modEventBus.addListener(SeismicContent::registerCapabilities);
        modEventBus.addListener(RichOreDataGen::gatherData);

        // Item carry weights: the pack's weight data map, which Encumbered is made to read (see EncumberedWeightMixin),
        // and a log of the items no weight group covers yet
        modEventBus.addListener(WeightDataMaps::register);
        NeoForge.EVENT_BUS.addListener(WeightReport::onDataMapsUpdated);

        // Frontier: how civilised each part of the world is. Its settings are per world (a SERVER config).
        FrontierContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, FrontierConfig.SPEC);

        // Food spoilage: the freshness component every food stack carries, and its stage lengths (a SERVER config of its own)
        FoodContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, SpoilageConfig.SPEC, SpoilageConfig.FILE_NAME);
        // AppleSkin's food previews show what spoiled food really gives (only if AppleSkin is installed)
        dev.brights0ng.enginesandempires.food.compat.AppleSkinCompat.registerIfLoaded();
        // Diet: spoiled food feeds its groups less, death resets them to 25% (only if Diet is installed)
        dev.brights0ng.enginesandempires.food.compat.DietCompat.registerIfLoaded();

        // Wind: the weather's wind pushes Sable's physics objects (its settings are a SERVER config of their own)
        dev.brights0ng.enginesandempires.weather.wind.WindContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, dev.brights0ng.enginesandempires.weather.wind.WindConfig.SPEC,
                dev.brights0ng.enginesandempires.weather.wind.WindConfig.FILE_NAME);
        // Weather: the climate baseline (biome climates in a data map), temperature, and rain and snow under the pack's
        // clouds (a SERVER config of its own)
        modEventBus.addListener(dev.brights0ng.enginesandempires.weather.climate.ClimateDataMaps::register);
        modContainer.registerConfig(ModConfig.Type.SERVER, dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.SPEC,
                dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.FILE_NAME);
        // Weather on the ground: glaze (freezing rain's ice layer) and the crops hail knocks back
        dev.brights0ng.enginesandempires.weather.surface.SurfaceContent.register(modEventBus);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Tell Create how much stress the mechanical thumper puts on a network. Must run after the block is registered,
        // which is guaranteed by this event firing after the registry events that create it.
        event.enqueueWork(SeismicContent::registerStressValues);
    }
}
