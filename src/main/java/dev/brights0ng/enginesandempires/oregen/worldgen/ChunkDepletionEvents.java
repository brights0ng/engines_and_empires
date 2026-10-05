package dev.brights0ng.enginesandempires.oregen.worldgen;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Keeps the deposit ledger's count of remaining ore up to date by recounting a chunk's ore whenever the game
 * saves it or unloads it.
 *
 * <p>Ore can only change while its chunk is loaded, so the count taken as a chunk leaves the world is exactly
 * right until the chunk is loaded again. That lets a deposit that players have mined out be recognised even
 * when nobody is anywhere near it, without having to read blocks from chunks that are not loaded. Hooking
 * the game's own saving means nothing depends on how ore was removed: a pickaxe, an explosion, a Create drill
 * or a piston all leave the same mark.
 *
 * <p>The game saves only chunks that have changed, so untouched chunks cost nothing. The work is on the main
 * thread and is small: deposits the ledger does not track, and ones with no ore in the chunk, are skipped
 * before anything is resolved.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class ChunkDepletionEvents {

    @SubscribeEvent
    public static void onChunkSave(ChunkDataEvent.Save event) {
        refresh(event.getLevel(), event.getChunk());
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        refresh(event.getLevel(), event.getChunk());
    }

    private static void refresh(LevelAccessor accessor, ChunkAccess chunk) {
        if (!(accessor instanceof ServerLevel level)) {
            return;
        }
        LevelDeposits deposits = OreMaps.existing(level); // not created here: a level that is shutting down may have lost its state
        if (deposits != null) {
            deposits.refreshChunk(chunk, level.getSeed());
        }
    }

    private ChunkDepletionEvents() {
    }
}
