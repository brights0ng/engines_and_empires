// Written by Claude.
package dev.brights0ng.empire.growth;

import java.util.ArrayDeque;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.CatchUpMath;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.GrowthClock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Catch-up for growth an empire chunk missed while it was not running (civilian chunk-loading plan §3.1; Bright, D1, D7,
 * D9). Every {@link #SCAN_TICKS} the scan checks each staked chunk: one that is growing now ({@link GrowthConditions})
 * after a gap since it last grew is queued. The queue runs within {@link #BUDGET_NANOS} per server tick: each block in
 * one of the two tags gets its own {@code randomTick} a Poisson number of times ({@link CatchUpMath}), so crop mods,
 * seasons and Dynamic Trees' own checks decide the result, under today's conditions.
 *
 * <p>One clock serves both tags. Dynamic Trees grows a soil only when the 8 blocks around it are entity-ticking
 * ({@code ChunkTreeHelper.isSurroundedByLoadedChunks}); its own check, run during the catch-up, applies that per block.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class GrowthCatchUp {

    /** Ticks between scans (provisional, P1). */
    public static final int SCAN_TICKS = 20;
    /** Catch-up time per server tick (provisional, P2: 2 ms). */
    public static final long BUDGET_NANOS = 2_000_000L;
    /** Crop-like blocks: up to {@link CatchUpMath#CROP_CAP} ticks each. */
    public static final TagKey<Block> CATCHES_UP = TagKey.create(Registries.BLOCK, Empire.id("catches_up"));
    /** Dynamic Trees soils and saplings: up to {@link CatchUpMath#TREE_CAP} ticks each. */
    public static final TagKey<Block> CATCHES_UP_TREES = TagKey.create(Registries.BLOCK, Empire.id("catches_up_trees"));

    /** Blocks looked at between checks of the time budget; a tagged block's ticks always end with a check. */
    private static final int BLOCKS_PER_TIME_CHECK = 256;

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();

    /** A chunk's catch-up, resumable across ticks. */
    private static final class Job {
        final long chunk;
        final long gap;
        /** The scan's time and the clock's time before it, to put back if the chunk stops running before any work. */
        final long scanTime;
        final long previous;
        int section;
        int index;
        boolean started;

        Job(long chunk, long gap, long scanTime, long previous) {
            this.chunk = chunk;
            this.gap = gap;
            this.scanTime = scanTime;
            this.previous = previous;
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (server.getTickCount() % SCAN_TICKS == 0) {
            scan(overworld);
        }
        runQueue(overworld, BUDGET_NANOS);
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
    }

    /** One scan of every empire's staked chunks (all in the Overworld). Public for the game tests. */
    public static void scan(ServerLevel overworld) {
        EmpireSavedData data = EmpireSavedData.of(overworld);
        GrowthClock clock = data.growthClock();
        long now = overworld.getGameTime();
        boolean changed = false;
        for (EmpireRecord empire : data.allEmpires()) {
            for (long chunk : empire.state().staked()) {
                if (!GrowthConditions.randomTicks(overworld, new ChunkPos(chunk))) {
                    continue;
                }
                long previous = clock.last(chunk);
                long gap = clock.markGrowing(chunk, now, SCAN_TICKS);
                changed = true;
                if (gap > 0) {
                    QUEUE.add(new Job(chunk, gap, now, previous));
                }
            }
        }
        if (changed) {
            data.setDirty();
        }
    }

    /** Runs queued catch-ups until the queue is empty or {@code budgetNanos} is spent. Public for the game tests. */
    public static void runQueue(ServerLevel overworld, long budgetNanos) {
        if (QUEUE.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        int speed = overworld.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        RandomSource random = overworld.getRandom();
        while (!QUEUE.isEmpty()) {
            if (!run(overworld, QUEUE.peek(), deadline, speed, random)) {
                return;
            }
            QUEUE.poll();
            if (System.nanoTime() >= deadline) {
                return;
            }
        }
    }

    /** How many catch-ups are waiting; for the game tests. */
    public static int queued() {
        return QUEUE.size();
    }

    /** Works on {@code job}; returns true when it is finished or dropped, false when the time ran out. */
    private static boolean run(ServerLevel level, Job job, long deadline, int speed, RandomSource random) {
        int cx = ChunkKeys.getX(job.chunk);
        int cz = ChunkKeys.getZ(job.chunk);
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null || !GrowthConditions.randomTicks(level, chunk.getPos())) {
            // Stopped running before its turn: leave the gap to be paid when it runs again. A half-done one keeps what it got.
            if (!job.started) {
                EmpireSavedData.of(level).growthClock().restore(job.chunk, job.scanTime, job.previous);
            }
            return true;
        }
        job.started = true;
        double lambda = CatchUpMath.lambda(job.gap, speed);
        LevelChunkSection[] sections = chunk.getSections();
        int minX = cx << 4;
        int minZ = cz << 4;
        int sinceCheck = 0;
        for (; job.section < sections.length; job.section++, job.index = 0) {
            LevelChunkSection section = sections[job.section];
            if (job.index == 0 && (section.hasOnlyAir() || !section.maybeHas(GrowthCatchUp::catchesUp))) {
                continue;
            }
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(job.section));
            for (; job.index < CatchUpMath.SECTION_BLOCKS; job.index++) {
                int x = job.index & 15;
                int z = (job.index >> 4) & 15;
                int y = job.index >> 8;
                BlockState state = section.getBlockState(x, y, z);
                if (catchesUp(state)) {
                    tickBlock(level, new BlockPos(minX + x, minY + y, minZ + z), state, lambda, random);
                    sinceCheck = BLOCKS_PER_TIME_CHECK;
                }
                if (++sinceCheck >= BLOCKS_PER_TIME_CHECK) {
                    sinceCheck = 0;
                    if (System.nanoTime() >= deadline) {
                        job.index++;
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Gives one block its missed random ticks; stops once it becomes something that does not catch up. */
    private static void tickBlock(ServerLevel level, BlockPos pos, BlockState state, double lambda, RandomSource random) {
        int cap = state.is(CATCHES_UP_TREES) ? CatchUpMath.TREE_CAP : CatchUpMath.CROP_CAP;
        int ticks = CatchUpMath.ticks(lambda, cap, random::nextDouble);
        for (int i = 0; i < ticks; i++) {
            if (!catchesUp(state) || !state.isRandomlyTicking()) {
                return;
            }
            state.randomTick(level, pos, random);
            state = level.getBlockState(pos);
        }
    }

    public static boolean catchesUp(BlockState state) {
        return state.is(CATCHES_UP) || state.is(CATCHES_UP_TREES);
    }

    private GrowthCatchUp() {
    }
}
