package dev.brights0ng.enginesandempires.frontier.deep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;

/**
 * Where the loaded sculk sensors (calibrated ones too) and shriekers are, per level, so a thumper shot can find the ones
 * within hundreds of blocks without searching the ground. Kept up to date as chunks load and unload and as blocks change
 * (through the same block-change hook Frontier uses). Server thread only.
 */
public final class SculkListeners {

    private static final Map<ServerLevel, SculkListeners> LEVELS = new WeakHashMap<>();

    /** Chunk ({@code ChunkPos.toLong}) to the sculk blocks in it ({@code BlockPos.asLong}). */
    private final Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = new Long2ObjectOpenHashMap<>();

    public static SculkListeners of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, l -> new SculkListeners());
    }

    static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    public static boolean isListener(BlockState state) {
        return state.getBlock() instanceof SculkSensorBlock || state.getBlock() instanceof SculkShriekerBlock;
    }

    void onChunkLoad(LevelChunk chunk) {
        LongOpenHashSet found = new LongOpenHashSet();
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (entity instanceof SculkSensorBlockEntity || entity instanceof SculkShriekerBlockEntity) {
                found.add(entity.getBlockPos().asLong());
            }
        }
        if (found.isEmpty()) {
            byChunk.remove(chunk.getPos().toLong());
        } else {
            byChunk.put(chunk.getPos().toLong(), found);
        }
    }

    void onChunkUnload(LevelChunk chunk) {
        byChunk.remove(chunk.getPos().toLong());
    }

    public void onBlockChanged(BlockPos pos, BlockState before, BlockState after) {
        boolean was = isListener(before);
        boolean now = isListener(after);
        if (was == now) {
            return;
        }
        long chunk = net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (now) {
            byChunk.computeIfAbsent(chunk, c -> new LongOpenHashSet()).add(pos.asLong());
        } else {
            LongOpenHashSet here = byChunk.get(chunk);
            if (here != null) {
                here.remove(pos.asLong());
                if (here.isEmpty()) {
                    byChunk.remove(chunk);
                }
            }
        }
    }

    /** Every known listener within {@code radius} blocks of {@code centre}. */
    public List<BlockPos> within(BlockPos centre, double radius) {
        List<BlockPos> found = new ArrayList<>();
        double squared = radius * radius;
        for (Long2ObjectMap.Entry<LongOpenHashSet> chunk : byChunk.long2ObjectEntrySet()) {
            for (LongIterator it = chunk.getValue().iterator(); it.hasNext(); ) {
                BlockPos pos = BlockPos.of(it.nextLong());
                if (pos.distSqr(centre) <= squared) {
                    found.add(pos);
                }
            }
        }
        return found;
    }

    public int size() {
        int n = 0;
        for (LongOpenHashSet here : byChunk.values()) {
            n += here.size();
        }
        return n;
    }
}
