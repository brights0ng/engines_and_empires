package dev.brights0ng.enginesandempires.frontier.deep;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/** Keeps the Deep's state going: sculk listeners as chunks come and go, pending triggers and stirred deposits as time passes. */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class DeepEvents {

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (level.getServer().isSameThread()) {
            SculkListeners.of(level).onChunkLoad(chunk);
        } else {
            level.getServer().execute(() -> {
                if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk) {
                    SculkListeners.of(level).onChunkLoad(chunk);
                }
            });
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && level.getServer().isSameThread()) {
            SculkListeners.of(level).onChunkUnload(chunk);
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Deep.of(level).tick();
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Deep.forget(level);
            SculkListeners.forget(level);
        }
    }

    /** Registers the Deep rosters' data-pack registry (mod bus; added in FrontierContent). */
    public static void registerRegistries(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(DeepRoster.KEY, DeepRoster.CODEC);
    }

    private DeepEvents() {
    }
}
