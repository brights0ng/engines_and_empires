// Edited by ChatGPT: registered weather instruments and their assets; creative tab registration added by Claude.
package dev.brights0ng.meteodyne;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.meteodyne.climate.ClimateDataMaps;
import dev.brights0ng.meteodyne.climate.MeteodyneTemperature;
import dev.brights0ng.meteodyne.gametest.GameTestCalendar;
import dev.brights0ng.meteodyne.heat.HeatField;
import dev.brights0ng.meteodyne.heat.world.HeatContent;
import dev.brights0ng.meteodyne.instrument.InstrumentContent;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.meteodyne.sable.MeteodyneSable;
import dev.brights0ng.meteodyne.sky.MeteodyneStorms;
import dev.brights0ng.meteodyne.surface.SurfaceContent;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.services.Services;
import dev.brights0ng.paradynelib.services.StormProvider;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import dev.brights0ng.paradynelib.services.WindProvider;
import net.minecraft.data.DataGenerator;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Meteodyne (clouds, weather, temperature): its id, logger and registration. A mod of its own since split step 2c; it
 * requires Paradyne Lib, and Sable and Iris are optional.
 */
@Mod(Meteodyne.MODID)
public final class Meteodyne {

    public static final String MODID = "meteodyne";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Meteodyne(IEventBus modEventBus, ModContainer modContainer) {
        init(modEventBus, modContainer);
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void init(IEventBus modEventBus, ModContainer modContainer) {
        // Weather: the climate baseline (biome climates in a data map), temperature, and rain and snow under the pack's
        // clouds (a SERVER config of its own)
        modEventBus.addListener(ClimateDataMaps::register);
        // Local heat and cold from fires, lava, ice and the like (a block data map) for snow, ice and water
        modEventBus.addListener(HeatField::register);
        // The heat simulation (rooms and air warmed by fires, furnaces, lava): its data maps and chunk data
        HeatContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, WeatherConfig.SPEC, WeatherConfig.FILE_NAME);
        // The game test server keeps the built-in calendar off, even when the config is reloaded from its file
        modEventBus.addListener(GameTestCalendar::onConfigReload);
        // Weather on the ground: glaze (freezing rain's ice layer) and the crops hail knocks back
        SurfaceContent.register(modEventBus);
        InstrumentContent.register(modEventBus);
        MeteodyneTab.register(modEventBus);
        // Ships (Sable sub-levels) as cover, rooms and decks. Sable is optional: everything that touches it is in the
        // sable sub-package, reached only from here, when it is loaded
        if (ModList.get().isLoaded("sable")) {
            MeteodyneSable.register(modEventBus);
        }

        // What the family's other mods ask of the weather (split seams S1-S3): temperatures (food), the wind (ships) and
        // storms (how dark it is for monsters)
        Services.register(TemperatureProvider.KEY, MeteodyneTemperature.INSTANCE);
        Services.register(WindProvider.KEY, WindSources.PROVIDER);
        Services.register(StormProvider.KEY, MeteodyneStorms.INSTANCE);
        modEventBus.addListener(Meteodyne::gatherData);
    }

    /** Meteodyne's data beyond glaze's assets ({@code SurfaceDataGen}): the empty structure its game tests build on. */
    private static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(event.includeServer(), new GameTestStructures(generator.getPackOutput(), MODID));
    }
}
