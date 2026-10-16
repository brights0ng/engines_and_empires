package dev.brights0ng.enginesandempires;

import org.slf4j.Logger;

import dev.brights0ng.frontier.Frontier;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.ParadyneLib;
import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import dev.brights0ng.simplespoilage.SimpleSpoilage;
import dev.brights0ng.sparseores.SparseOres;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * The one mod that holds the whole family until the split's step 2: it only hands the mod bus to each future mod's
 * entry point, in dependency order (the library first, the core last).
 */
@Mod(EnginesAndEmpires.MODID)
public class EnginesAndEmpiresMod {

    public static final String MODID = EnginesAndEmpires.MODID;
    public static final Logger LOGGER = EnginesAndEmpires.LOGGER;

    public EnginesAndEmpiresMod(IEventBus modEventBus, ModContainer modContainer) {
        ParadyneLib.init(modEventBus, modContainer);
        SparseOres.init(modEventBus, modContainer);
        SeismicSurveyance.init(modEventBus, modContainer);
        Meteodyne.init(modEventBus, modContainer);
        Frontier.init(modEventBus, modContainer);
        SimpleSpoilage.init(modEventBus, modContainer);
        EnginesAndEmpires.init(modEventBus, modContainer);
    }
}
