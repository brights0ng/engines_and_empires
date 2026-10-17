package dev.brights0ng.enginesandempires;

import dev.brights0ng.frontier.Frontier;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import dev.brights0ng.simplespoilage.SimpleSpoilage;
import dev.brights0ng.sparseores.SparseOres;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * The one mod that holds the family's mods not yet sliced into mods of their own (split step 2): it only hands the mod
 * bus to each future mod's entry point, in dependency order (the core last). Paradyne Lib is a mod of its own since
 * step 2b and loads before this one.
 */
@Mod(EnginesAndEmpires.MODID)
public class EnginesAndEmpiresMod {

    public EnginesAndEmpiresMod(IEventBus modEventBus, ModContainer modContainer) {
        SparseOres.init(modEventBus, modContainer);
        SeismicSurveyance.init(modEventBus, modContainer);
        Meteodyne.init(modEventBus, modContainer);
        Frontier.init(modEventBus, modContainer);
        SimpleSpoilage.init(modEventBus, modContainer);
        EnginesAndEmpires.init(modEventBus, modContainer);
    }
}
