package dev.brights0ng.enginesandempires.oregen.worldgen;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.Realm;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Sets up each dimension's deposit state when the level loads, on the main thread and before any
 * chunk generates, and drops it when the level unloads. Loading the saved ledger has to happen here,
 * because the level's data storage is not safe to touch from worldgen threads.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class LevelDepositEvents {

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && hasDeposits(level)) {
            OreMaps.attach(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && hasDeposits(level)) {
            OreMaps.detach(level);
        }
    }

    private static boolean hasDeposits(ServerLevel level) {
        return Realm.ofDimension(level.dimension().location().toString()) != null;
    }

    private LevelDepositEvents() {
    }
}
