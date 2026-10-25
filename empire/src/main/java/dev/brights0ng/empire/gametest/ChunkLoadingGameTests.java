// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.UUID;
import java.util.List;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.growth.GrowthConditions;
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
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Phase 0 of the civilian chunk-loading plan: verifies the chunk-loading facts the plan rests on, with no feature code.
 * Far from any player (a game test server has none), with {@code randomTickSpeed} raised:
 * <ul>
 * <li>wheat in a chunk held by a NeoForge <em>ticking</em> forced ticket grows;</li>
 * <li>wheat in a chunk held by a <em>non-ticking</em> forced ticket (same level, 31) does not;</li>
 * <li>wheat in the ring chunk next to the ticking one (level 32: block updates, no entities) does not.</li>
 * </ul>
 * In its own batch, as it changes a game rule every test in a batch would see.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = Empire.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChunkLoadingGameTests {

    private static final String BATCH = "chunkloading";
    /** Test-only controller; its saved tickets are all dropped on load. */
    static final TicketController TEST_TICKETS = new TicketController(Empire.id("gametest"),
            (level, helper) -> List.copyOf(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));
    private static final UUID OWNER = UUID.fromString("6d1a7c2e-0f3b-4b8e-9a41-5c0e2f7d9b13");
    /** Chunks east of the test area: far from it and from spawn. */
    private static final int OFFSET_CHUNKS = 256;
    /** Between the ticking and non-ticking sites, so their rings do not touch. */
    private static final int SITE_GAP = 16;
    private static final int Y = 300;
    private static final int TICK_SPEED = 2048;
    private static final int GROW_TICKS = 200;
    /** Ticking wheat must reach at least this age; the expected age at these settings is full (7). */
    private static final int MIN_AGE = 3;

    @SubscribeEvent
    static void registerTicketControllers(RegisterTicketControllersEvent event) {
        event.register(TEST_TICKETS);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 1200)
    public static void tickingTicketRandomTicksOnlyItsChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos ticking = new ChunkPos(origin.x + OFFSET_CHUNKS, origin.z);
        ChunkPos ring = new ChunkPos(ticking.x + 1, ticking.z);
        ChunkPos nonTicking = new ChunkPos(ticking.x + SITE_GAP, ticking.z);
        GameRules.IntegerValue rule = level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
        int oldSpeed = rule.get();
        long[] plantedAt = new long[1];

        TEST_TICKETS.forceChunk(level, OWNER, ticking.x, ticking.z, true, true);
        TEST_TICKETS.forceChunk(level, OWNER, nonTicking.x, nonTicking.z, true, false);
        Runnable cleanUp = () -> {
            rule.set(oldSpeed, level.getServer());
            TEST_TICKETS.forceChunk(level, OWNER, ticking.x, ticking.z, false, true);
            TEST_TICKETS.forceChunk(level, OWNER, nonTicking.x, nonTicking.z, false, false);
        };

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(level.isNaturalSpawningAllowed(ticking), "ticking site is entity-ticking");
                    helper.assertTrue(level.isNaturalSpawningAllowed(nonTicking), "non-ticking site is entity-ticking");
                    helper.assertTrue(level.shouldTickBlocksAt(ring.toLong()), "ring chunk runs block updates");
                })
                .thenExecute(() -> {
                    helper.assertFalse(level.isNaturalSpawningAllowed(ring), "ring chunk is not entity-ticking (level 32)");
                    // Empire's own reading of the rule agrees with what happens below.
                    helper.assertTrue(GrowthConditions.randomTicks(level, ticking), "GrowthConditions: ticking site grows");
                    helper.assertFalse(GrowthConditions.randomTicks(level, nonTicking), "GrowthConditions: non-ticking site does not");
                    helper.assertFalse(GrowthConditions.randomTicks(level, ring), "GrowthConditions: ring does not");
                    plantWheat(level, ticking);
                    plantWheat(level, ring);
                    plantWheat(level, nonTicking);
                    rule.set(TICK_SPEED, level.getServer());
                    plantedAt[0] = level.getGameTime();
                })
                .thenIdle(GROW_TICKS)
                .thenExecute(() -> {
                    int tickingAge = wheatAge(level, ticking);
                    int ringAge = wheatAge(level, ring);
                    int nonTickingAge = wheatAge(level, nonTicking);
                    cleanUp.run();
                    String ages = " (ages: ticking " + tickingAge + ", ring " + ringAge + ", non-ticking " + nonTickingAge
                            + "; " + (level.getGameTime() - plantedAt[0]) + " ticks at randomTickSpeed " + TICK_SPEED + ")";
                    Empire.LOGGER.info("Chunk-loading phase 0{}", ages);
                    helper.assertTrue(tickingAge >= MIN_AGE, "wheat under a ticking ticket grows" + ages);
                    helper.assertTrue(nonTickingAge == 0, "wheat under a non-ticking ticket does not grow" + ages);
                    helper.assertTrue(ringAge == 0, "wheat in the level-32 ring does not grow" + ages);
                })
                .thenSucceed();
    }

    private static BlockPos site(ChunkPos chunk) {
        return new BlockPos(chunk.getMiddleBlockX(), Y, chunk.getMiddleBlockZ());
    }

    /** Wet farmland with age-0 wheat on it, high in the air (full sky light), and glowstone beside it. */
    private static void plantWheat(ServerLevel level, ChunkPos chunk) {
        BlockPos farmland = site(chunk);
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 3);
        level.setBlock(farmland.above(), Blocks.WHEAT.defaultBlockState(), 3);
        level.setBlock(farmland.above().east(), Blocks.GLOWSTONE.defaultBlockState(), 3);
    }

    /** The wheat's age, or -1 if it is gone. */
    private static int wheatAge(ServerLevel level, ChunkPos chunk) {
        BlockState state = level.getBlockState(site(chunk).above());
        return state.is(Blocks.WHEAT) ? state.getValue(CropBlock.AGE) : -1;
    }

    private ChunkLoadingGameTests() {
    }
}
