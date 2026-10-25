// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.growth.GrowthCatchUp;
import dev.brights0ng.empire.growth.GrowthConditions;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.GrowthClock;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.empire.rules.UnstakeResult;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Phase 1 of the civilian chunk-loading plan: the catch-up ({@link GrowthCatchUp}). Each test founds its empire far east
 * of its own area (its own offset, so none overlap) and holds its centre chunk with a ticking ticket from
 * {@link ChunkLoadingGameTests#TEST_TICKETS}, so it grows with no player near. In its own batch, as it sets a game rule.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class GrowthCatchUpGameTests {

    private static final String BATCH = "catchup";
    private static final int Y = 300;
    /** Far enough back that every block's draw is the cap. */
    private static final long LONG_AGO = 200_000L;
    private static final int VANILLA_TICK_SPEED = 3;

    private static ChunkPos centre(GameTestHelper helper, int offsetChunks) {
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        return new ChunkPos(origin.x + offsetChunks, origin.z);
    }

    private static EmpireRecord found(GameTestHelper helper, ChunkPos centre) {
        return EmpireSavedData.of(helper.getLevel()).createEmpire(UUID.randomUUID(), centre.toLong(), null,
                helper.getLevel().getGameTime());
    }

    private static BlockPos site(ChunkPos chunk) {
        return new BlockPos(chunk.getMiddleBlockX(), Y, chunk.getMiddleBlockZ());
    }

    /** Age-0 wheat on a 3x3 of wet farmland (a 1 in 3 chance to grow per random tick), in the air at full sky light. */
    private static void plantWheat(ServerLevel level, ChunkPos chunk) {
        BlockPos centre = site(chunk);
        BlockState wet = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(centre.offset(dx, 0, dz), wet, 3);
            }
        }
        level.setBlock(centre.above(), Blocks.WHEAT.defaultBlockState(), 3);
    }

    private static int wheatAge(ServerLevel level, ChunkPos chunk) {
        BlockState state = level.getBlockState(site(chunk).above());
        return state.is(Blocks.WHEAT) ? state.getValue(CropBlock.AGE) : -1;
    }

    private static BlockPos saplingPos(ChunkPos chunk) {
        return site(chunk).offset(4, 1, 0);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 1200)
    public static void aGapIsCaughtUpOnlyWhereTheChunkRuns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos centre = centre(helper, 512);
        ChunkPos ring = new ChunkPos(centre.x + 1, centre.z);
        EmpireRecord record = found(helper, centre);
        GrowthClock clock = EmpireSavedData.of(level).growthClock();
        GameRules.IntegerValue rule = level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
        int oldSpeed = rule.get();
        long[] ringSetTo = new long[1];
        ChunkLoadingGameTests.TEST_TICKETS.forceChunk(level, record.id(), centre.x, centre.z, true, true);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(GrowthConditions.randomTicks(level, centre), "the centre grows"))
                .thenExecute(() -> {
                    helper.assertFalse(GrowthConditions.randomTicks(level, ring), "the level-32 ring does not grow");
                    rule.set(VANILLA_TICK_SPEED, level.getServer());
                    plantWheat(level, centre);
                    plantWheat(level, ring);
                    level.setBlock(saplingPos(centre).below(), Blocks.DIRT.defaultBlockState(), 3);
                    level.setBlock(saplingPos(centre), Blocks.OAK_SAPLING.defaultBlockState(), 3);
                    long now = level.getGameTime();
                    clock.set(centre.toLong(), now - LONG_AGO);
                    ringSetTo[0] = now - LONG_AGO;
                    clock.set(ring.toLong(), ringSetTo[0]);
                })
                .thenWaitUntil(() -> helper.assertTrue(wheatAge(level, centre) == CropBlock.MAX_AGE,
                        "the centre's wheat catches up to full age, at " + wheatAge(level, centre)))
                .thenIdle(GrowthCatchUp.SCAN_TICKS * 2)
                .thenExecute(() -> {
                    rule.set(oldSpeed, level.getServer());
                    BlockState sapling = level.getBlockState(saplingPos(centre));
                    helper.assertTrue(sapling.is(Blocks.OAK_SAPLING) && sapling.getValue(SaplingBlock.STAGE) == 0,
                            "an untagged vanilla sapling is not caught up, got " + sapling);
                    helper.assertTrue(clock.last(centre.toLong()) > level.getGameTime() - LONG_AGO,
                            "the centre's clock has moved on");
                    helper.assertTrue(wheatAge(level, ring) == 0, "the ring's wheat is untouched, at " + wheatAge(level, ring));
                    helper.assertTrue(clock.last(ring.toLong()) == ringSetTo[0], "the ring's clock is unchanged");
                    ChunkLoadingGameTests.TEST_TICKETS.forceChunk(level, record.id(), centre.x, centre.z, false, true);
                    EmpireSavedData.of(level).disbandEmpire(record.id(), null);
                })
                .thenSucceed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 1200)
    public static void aFreshChunkGetsNoBackPay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos centre = centre(helper, 528);
        EmpireRecord record = found(helper, centre);
        EmpireSavedData data = EmpireSavedData.of(level);
        GrowthClock clock = data.growthClock();
        ChunkLoadingGameTests.TEST_TICKETS.forceChunk(level, record.id(), centre.x, centre.z, true, true);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(GrowthConditions.randomTicks(level, centre), "the centre grows"))
                .thenExecute(() -> plantWheat(level, centre))
                .thenIdle(GrowthCatchUp.SCAN_TICKS * 3 + 5)
                .thenExecute(() -> {
                    helper.assertTrue(clock.last(centre.toLong()) != GrowthClock.NEVER, "the first sighting sets the clock");
                    helper.assertTrue(wheatAge(level, centre) <= 1, "no back pay on the first sighting, at " + wheatAge(level, centre));

                    // A chunk with an old time, staked afresh, starts with none.
                    long next = ChunkKeys.pack(centre.x + 2, centre.z);
                    clock.set(next, 5L);
                    StakeResult staked = data.stakeChunk(record, next, 0L, true);
                    helper.assertTrue(staked == StakeResult.OK, "staked the next chunk, got " + staked);
                    helper.assertTrue(clock.last(next) == GrowthClock.NEVER, "staking clears the chunk's time");
                    ChunkLoadingGameTests.TEST_TICKETS.forceChunk(level, record.id(), centre.x, centre.z, false, true);
                    data.disbandEmpire(record.id(), null);
                })
                .thenSucceed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void unstakingAndDisbandingForgetTimes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos centre = centre(helper, 544);
        EmpireSavedData data = EmpireSavedData.of(level);
        GrowthClock clock = data.growthClock();
        clock.set(centre.toLong(), 7L);
        EmpireRecord record = found(helper, centre);
        helper.assertTrue(clock.last(centre.toLong()) == GrowthClock.NEVER, "founding clears its chunks' times");

        long outer = ChunkKeys.pack(centre.x + 2, centre.z);
        helper.assertTrue(data.stakeChunk(record, outer, 0L, true) == StakeResult.OK, "staked an outer chunk");
        clock.set(outer, 100L);
        UnstakeResult unstaked = data.unstakeChunk(record, outer, 0L);
        helper.assertTrue(unstaked == UnstakeResult.OK, "unstaked it, got " + unstaked);
        helper.assertTrue(clock.last(outer) == GrowthClock.NEVER, "unstaking forgets its time");

        clock.set(centre.toLong(), 100L);
        data.disbandEmpire(record.id(), null);
        helper.assertTrue(clock.last(centre.toLong()) == GrowthClock.NEVER, "disbanding forgets its chunks' times");
        helper.succeed();
    }

    private GrowthCatchUpGameTests() {
    }
}
