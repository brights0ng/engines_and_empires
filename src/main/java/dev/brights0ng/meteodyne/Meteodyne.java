package dev.brights0ng.meteodyne;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.meteodyne.climate.ClimateDataMaps;
import dev.brights0ng.meteodyne.climate.MeteodyneTemperature;
import dev.brights0ng.meteodyne.heat.HeatField;
import dev.brights0ng.meteodyne.heat.world.HeatContent;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.meteodyne.surface.SurfaceContent;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.services.Services;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import dev.brights0ng.paradynelib.services.WindProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

/**
 * Meteodyne (clouds, weather, temperature): its id, logger and registration. Still part of the one engines_and_empires
 * mod for now: {@link #MODID} becomes "meteodyne" when the mod is cut out (split step 2).
 */
public final class Meteodyne {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    private Meteodyne() {
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        // Weather: the climate baseline (biome climates in a data map), temperature, and rain and snow under the pack's
        // clouds (a SERVER config of its own)
        modEventBus.addListener(ClimateDataMaps::register);
        // Local heat and cold from fires, lava, ice and the like (a block data map) for snow, ice and water
        modEventBus.addListener(HeatField::register);
        // The heat simulation (rooms and air warmed by fires, furnaces, lava): its data maps and chunk data
        HeatContent.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, WeatherConfig.SPEC, WeatherConfig.FILE_NAME);
        // Weather on the ground: glaze (freezing rain's ice layer) and the crops hail knocks back
        SurfaceContent.register(modEventBus);

        // What the family's other mods ask of the weather (split seams S1-S3): temperatures (food) and the wind (ships)
        Services.register(TemperatureProvider.KEY, MeteodyneTemperature.INSTANCE);
        Services.register(WindProvider.KEY, WindSources.PROVIDER);
    }
}
