package dev.brights0ng.enginesandempires;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.enginesandempires.food.compat.AppleSkinCompat;
import dev.brights0ng.enginesandempires.food.compat.DietCompat;
import dev.brights0ng.enginesandempires.weather.climate.compat.ColdSweatCompat;
import dev.brights0ng.enginesandempires.weather.climate.compat.SereneSeasonsCompat;
import dev.brights0ng.enginesandempires.weight.WeightDataMaps;
import dev.brights0ng.enginesandempires.weight.WeightReport;
import dev.brights0ng.sparseores.refining.RefiningItems;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Engines and Empires, the core modpack mod (the glue between the family's mods and the pack's other mods, refining,
 * carry weight): its id, logger and registration. Its id is the one the single mod has today, and it keeps it after
 * the split.
 */
public final class EnginesAndEmpires {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    private EnginesAndEmpires() {
    }

    /** An id in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        // The chips (coal, diamond, quartz): what those ores drop now instead of a whole item
        RefiningItems.register(modEventBus);

        // Item carry weights: the pack's weight data map, which Encumbered is made to read (see EncumberedWeightMixin),
        // and a log of the items no weight group covers yet
        modEventBus.addListener(WeightDataMaps::register);
        NeoForge.EVENT_BUS.addListener(WeightReport::onDataMapsUpdated);

        // AppleSkin's food previews show what spoiled food really gives (only if AppleSkin is installed)
        AppleSkinCompat.registerIfLoaded();
        // Diet: spoiled food feeds its groups less, death resets them to 25% (only if Diet is installed)
        DietCompat.registerIfLoaded();

        // Cold Sweat: the pack's air temperature becomes its world temperature in the Overworld (only if Cold Sweat is installed)
        ColdSweatCompat.registerIfLoaded(modEventBus);
        // Serene Seasons: its calendar runs Meteodyne's year in place of the built-in one (only if Serene Seasons is installed)
        SereneSeasonsCompat.registerIfLoaded();
    }
}
