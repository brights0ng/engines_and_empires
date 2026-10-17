package dev.brights0ng.frontier.deep;

import dev.brights0ng.frontier.Frontier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/**
 * Keeps track of the sculk listeners the Deep wakes, as chunks come and go. The Deep's own ticking (pending triggers,
 * stirred deposits) is hooked up with Seismic Surveyance's shots, in {@code frontier.seismic.FrontierSeismic}.
 */
@EventBusSubscriber(modid = Frontier.MODID)
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
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
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
