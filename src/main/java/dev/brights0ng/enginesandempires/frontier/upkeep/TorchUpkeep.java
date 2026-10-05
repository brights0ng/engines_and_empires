package dev.brights0ng.enginesandempires.frontier.upkeep;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.FrontierContent;
import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierChunkData;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Torches burning out: outside Settled land a torch goes out a day after it was lit, becoming a dry torch that a right-click
 * lights again. In Settled land torches are looked after (their clocks keep being reset), so their day only starts once the
 * land stops being Settled. See {@link TorchClock}.
 *
 * <p>Each chunk remembers when each of its torches was lit (in {@link FrontierChunkData}), so time passes while it is unloaded
 * too: come back to an outpost after a few days and its torches are out. Torches are found by the same block-change hook
 * that counts anchors, and a chunk's existing torches (village torches, for one) are found the first time it loads.
 */
public final class TorchUpkeep {

    private final FrontierLevel frontier;
    private final ServerLevel level;
    /** The loaded chunks that have torches, so the sweep does not have to look at every chunk. */
    private final LongOpenHashSet chunksWithTorches = new LongOpenHashSet();

    public TorchUpkeep(FrontierLevel frontier) {
        this.frontier = frontier;
        this.level = frontier.level();
    }

    public void onChunkLoad(LevelChunk chunk, FrontierChunkData data) {
        if (!data.torchesScanned()) {
            data.setTorchesScanned(findTorches(chunk), level.getGameTime());
            chunk.setUnsaved(true);
        }
        if (data.hasTorches()) {
            chunksWithTorches.add(chunk.getPos().toLong());
        }
    }

    public void onChunkUnload(LevelChunk chunk) {
        chunksWithTorches.remove(chunk.getPos().toLong());
    }

    /** A torch was placed (or lit again) at {@code pos}, or taken away. */
    public void onTorchChanged(LevelChunk chunk, FrontierChunkData data, BlockPos pos, boolean placed) {
        if (!data.torchesScanned()) {
            return; // it will be found when the chunk is scanned
        }
        if (placed) {
            data.torchLit(pos.asLong(), level.getGameTime());
            chunksWithTorches.add(chunk.getPos().toLong());
            chunk.setUnsaved(true);
        } else if (data.torchRemoved(pos.asLong())) {
            chunk.setUnsaved(true);
        }
    }

    public void tick() {
        if (level.getGameTime() % FrontierConfig.torchSweepInterval() == 0) {
            sweep();
        }
    }

    /** Looks at every loaded torch: looks after the ones in Settled land, and puts out the ones whose day is up. */
    public int sweep() {
        long now = level.getGameTime();
        long burnTicks = FrontierConfig.torchBurnTicks();
        LongArrayList toPutOut = new LongArrayList();
        for (LongIterator chunks = chunksWithTorches.iterator(); chunks.hasNext(); ) {
            long chunkKey = chunks.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
                chunks.remove();
                continue;
            }
            FrontierChunkData data = chunk.getData(FrontierContent.CHUNK_DATA);
            if (!data.hasTorches()) {
                chunks.remove();
                continue;
            }
            boolean tended = false;
            for (Long2LongMap.Entry torch : data.torches().long2LongEntrySet()) {
                BlockPos pos = BlockPos.of(torch.getLongKey());
                boolean settled = frontier.tierAt(pos).atLeast(Tier.SETTLED);
                switch (TorchClock.decide(torch.getLongValue(), now, settled, burnTicks)) {
                    case TEND -> {
                        torch.setValue(now);
                        tended = true;
                    }
                    case BURN_OUT -> toPutOut.add(torch.getLongKey());
                    case KEEP -> {
                    }
                }
            }
            if (tended) {
                chunk.setUnsaved(true);
            }
        }
        // Put out afterwards: changing the blocks updates the same maps through the block-change hook.
        int putOut = 0;
        for (int i = 0; i < toPutOut.size(); i++) {
            BlockPos pos = BlockPos.of(toPutOut.getLong(i));
            BlockState state = level.getBlockState(pos);
            if (state.is(FrontierTags.BURNS_OUT)) {
                level.setBlock(pos, DryTorches.dryFor(state), Block.UPDATE_ALL);
                putOut++;
            } else {
                forget(pos); // it was changed some way the hook did not see
            }
        }
        return putOut;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Debug and tests

    /** When the torch at {@code pos} was lit, or null if no torch is known there. */
    public Long litAt(BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !chunk.hasData(FrontierContent.CHUNK_DATA)) {
            return null;
        }
        Long2LongMap torches = chunk.getData(FrontierContent.CHUNK_DATA).torches();
        return torches.containsKey(pos.asLong()) ? torches.get(pos.asLong()) : null;
    }

    /** Makes every known torch within {@code radius} blocks of {@code centre} as if it had been lit {@code ticks} earlier. */
    public int age(BlockPos centre, int radius, long ticks) {
        int aged = 0;
        for (int cx = (centre.getX() - radius) >> 4; cx <= (centre.getX() + radius) >> 4; cx++) {
            for (int cz = (centre.getZ() - radius) >> 4; cz <= (centre.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || !chunk.hasData(FrontierContent.CHUNK_DATA)) {
                    continue;
                }
                for (Long2LongMap.Entry torch : chunk.getData(FrontierContent.CHUNK_DATA).torches().long2LongEntrySet()) {
                    if (BlockPos.of(torch.getLongKey()).closerThan(centre, radius + 0.5)) {
                        torch.setValue(torch.getLongValue() - ticks);
                        aged++;
                    }
                }
                chunk.setUnsaved(true);
            }
        }
        return aged;
    }

    private void forget(BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && chunk.getData(FrontierContent.CHUNK_DATA).torchRemoved(pos.asLong())) {
            chunk.setUnsaved(true);
        }
    }

    /** The positions of the torches in a chunk, skipping sections that cannot hold any. */
    private static long[] findTorches(LevelChunk chunk) {
        LongArrayList found = new LongArrayList();
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()
                    || !section.getStates().maybeHas(state -> state.is(FrontierTags.BURNS_OUT))) {
                continue;
            }
            int baseY = (chunk.getMinSection() + i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).is(FrontierTags.BURNS_OUT)) {
                            found.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
        return found.toLongArray();
    }
}
