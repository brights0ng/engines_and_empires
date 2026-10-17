package dev.brights0ng.enginesandempires;

import dev.brights0ng.frontier.Frontier;
import dev.brights0ng.simplespoilage.SimpleSpoilage;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * The one mod that holds the family's mods not yet sliced into mods of their own (split step 2): it only hands the mod
 * bus to each future mod's entry point, in dependency order (the core last). Paradyne Lib (since step 2b), Meteodyne
 * (since step 2c), Sparse Ores and Seismic Surveyance (since step 2e) are mods of their own and load before this one.
 */
@Mod(EnginesAndEmpires.MODID)
public class EnginesAndEmpiresMod {

    public EnginesAndEmpiresMod(IEventBus modEventBus, ModContainer modContainer) {
        Frontier.init(modEventBus, modContainer);
        SimpleSpoilage.init(modEventBus, modContainer);
        EnginesAndEmpires.init(modEventBus, modContainer);
    }
}
