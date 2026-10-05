package dev.brights0ng.enginesandempires.oregen.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import dev.brights0ng.enginesandempires.oregen.RemainingOre;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Lets {@link RemainingOre} look at a level's blocks, but only where they can be read for free: in chunks that
 * are already loaded. It never loads a chunk, or asks for one to be generated, so checking a deposit
 * that is nowhere near a player costs nothing.
 *
 * <p>Reading blocks is only safe on the server's main thread, so this must only be used there. It remembers the
 * chunks it looks up for as long as it lives, so make a new one for each batch of checks.
 */
public final class LevelWorldView implements RemainingOre.World {

    private final ServerChunkCache chunks;
    private final Map<Long, LevelChunk> seen = new HashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private String lastOreId;
    private Set<Block> lastOres;

    public LevelWorldView(ServerLevel level) {
        this.chunks = level.getChunkSource();
    }

    @Override
    public boolean isLoaded(int chunkX, int chunkZ) {
        return chunk(chunkX, chunkZ) != null;
    }

    @Override
    public boolean isOre(String oreId, int x, int y, int z) {
        LevelChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk == null) {
            return false;
        }
        if (!oreId.equals(lastOreId)) {
            lastOres = OreBlocks.oreBlocks(oreId);
            lastOreId = oreId;
        }
        return lastOres.contains(chunk.getBlockState(pos.set(x, y, z)).getBlock());
    }

    /** The chunk if it is loaded, otherwise null. Never loads it. */
    private LevelChunk chunk(int chunkX, int chunkZ) {
        long key = ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
        if (seen.containsKey(key)) {
            return seen.get(key);
        }
        LevelChunk chunk = chunks.getChunkNow(chunkX, chunkZ);
        seen.put(key, chunk);
        return chunk;
    }
}
