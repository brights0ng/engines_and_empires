// Written by Claude.
package dev.brights0ng.empire.growth;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Whether a chunk's blocks are getting random ticks right now: vanilla's rule in {@code ServerChunkCache.tickChunks},
 * with NeoForge's ticking forced tickets. Verified by {@code gametest.ChunkLoadingGameTests} (2026-10-25).
 */
public final class GrowthConditions {

    /**
     * The chunk is entity-ticking (level 31 or lower; a level-32 chunk gets no random ticks), and either a non-spectator
     * player is within 128 blocks of its centre or a ticking forced ticket holds it (Empire's civilians, or any other
     * mod's). Counting every ticking ticket, not only Empire's, keeps a chunk that grew on its own from being paid twice.
     */
    public static boolean randomTicks(ServerLevel level, ChunkPos pos) {
        if (!level.isNaturalSpawningAllowed(pos)) {
            return false;
        }
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        return chunkMap.getDistanceManager().shouldForceTicks(pos.toLong()) || !chunkMap.getPlayersCloseForSpawning(pos).isEmpty();
    }

    private GrowthConditions() {
    }
}
