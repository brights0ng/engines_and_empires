package dev.brights0ng.enginesandempires;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * The core mod's entry point. Since split step 2d every other mod of the family is a mod of its own (Paradyne Lib and
 * Sable Fixes since step 2b, Meteodyne since 2c, Sparse Ores and Seismic Surveyance since 2e, Simple Spoilage and
 * Frontier since 2d) and loads before this one, so it only hands the mod bus to the core's own registration.
 */
@Mod(EnginesAndEmpires.MODID)
public class EnginesAndEmpiresMod {

    public EnginesAndEmpiresMod(IEventBus modEventBus, ModContainer modContainer) {
        EnginesAndEmpires.init(modEventBus, modContainer);
    }
}
