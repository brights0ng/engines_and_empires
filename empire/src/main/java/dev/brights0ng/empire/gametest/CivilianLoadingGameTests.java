// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianChunkLoading;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.LoadPlan;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Phase 2 of the civilian chunk-loading plan: civilians' tickets ({@link CivilianChunkLoading}). Each test founds an
 * empire far east of its area (its own offset) with one citizen record whose last chunk the test sets. The record's
 * body is an armor stand in the test area, so the lost-body check finds a body and spawns no civilian that would wander
 * and move the record. The founder is never online, so loading rests on the 5-day window (founding counts as online).
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianLoadingGameTests {

    private static final String BATCH = "civilianloading";
    /** Apart from the others: it switches the mode for the whole server. */
    private static final String OFF_BATCH = "civilianloading_off";

    /** One test's empire, its centre chunk and its citizen. */
    private record Setup(EmpireRecord empire, ChunkPos centre, CivilianRecord civilian, Entity body) {
    }

    private static Setup setUp(GameTestHelper helper, int offsetChunks) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level);
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(origin.x + offsetChunks, origin.z);
        EmpireRecord empire = data.createEmpire(UUID.randomUUID(), centre.toLong(), null, level.getGameTime());
        Entity body = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(1, 1, 1));
        CivilianRecord civilian = new CivilianRecord(UUID.randomUUID(), empire.id(), body.getUUID(), "Stand-in", 0,
                centre.getMiddleBlockPosition(64), centre.toLong(), level.getGameTime());
        data.addCivilian(civilian);
        return new Setup(empire, centre, civilian, body);
    }

    private static void tearDown(GameTestHelper helper, Setup setup) {
        EmpireSavedData.of(helper.getLevel()).disbandEmpire(setup.empire().id(), null);
        CivilianChunkLoading.recompute(helper.getLevel());
        setup.body().discard();
    }

    private static long c(ChunkPos centre, int dx, int dz) {
        return ChunkKeys.pack(centre.x + dx, centre.z + dz);
    }

    /** The chunks with x offsets {@code fromDx..toDx} and z offsets -1..1 from the centre. */
    private static Set<Long> columns(ChunkPos centre, int fromDx, int toDx) {
        Set<Long> chunks = new HashSet<>();
        for (int dx = fromDx; dx <= toDx; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunks.add(c(centre, dx, dz));
            }
        }
        return chunks;
    }

    private static boolean forced(ServerLevel level, long chunk) {
        return level.getChunkSource().chunkMap.getDistanceManager().shouldForceTicks(chunk);
    }

    private static void move(CivilianRecord civilian, long chunk) {
        civilian.setLast(new ChunkPos(chunk).getMiddleBlockPosition(64), chunk);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 400)
    public static void ticketsAreTheStakedThreeByThreeAroundEachCivilian(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setUp(helper, 600);
        ChunkPos centre = setup.centre();
        UUID id = setup.empire().id();
        // On the east edge: its 3x3 reaches one column past the empire's land.
        move(setup.civilian(), c(centre, 1, 0));
        CivilianChunkLoading.recompute(level);
        Set<Long> expected = columns(centre, 0, 1);
        helper.assertTrue(CivilianChunkLoading.held(id).equals(expected),
                "the staked 6 of the 3x3, got " + CivilianChunkLoading.held(id).size());

        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (long chunk : expected) {
                        helper.assertTrue(forced(level, chunk), "a ticking ticket holds " + new ChunkPos(chunk));
                    }
                })
                .thenExecute(() -> {
                    helper.assertFalse(forced(level, c(centre, 2, 0)), "no ticket on unstaked land");
                    StakeResult staked = EmpireSavedData.of(level).stakeChunk(setup.empire(), c(centre, 2, 0), 0L, true);
                    helper.assertTrue(staked == StakeResult.OK, "staked the next chunk, got " + staked);
                    CivilianChunkLoading.recompute(level);
                    helper.assertTrue(CivilianChunkLoading.held(id).contains(c(centre, 2, 0)), "a newly staked chunk is loaded");
                    tearDown(helper, setup);
                    helper.assertTrue(CivilianChunkLoading.held(id).isEmpty(), "a disbanded empire holds nothing");
                })
                .thenWaitUntil(() -> helper.assertFalse(forced(level, c(centre, 0, 0)), "the tickets are released"))
                .thenSucceed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 1500)
    public static void anUnwantedChunkIsKeptForTheDelay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setUp(helper, 620);
        ChunkPos centre = setup.centre();
        UUID id = setup.empire().id();
        CivilianChunkLoading.recompute(level);
        helper.assertTrue(CivilianChunkLoading.held(id).size() == 9, "the whole 3x3, got " + CivilianChunkLoading.held(id).size());

        // Walks west: the east column is no longer wanted.
        move(setup.civilian(), c(centre, -1, 0));
        CivilianChunkLoading.recompute(level);
        long movedAt = level.getGameTime();
        long east = c(centre, 1, 0);
        helper.assertTrue(CivilianChunkLoading.held(id).contains(east), "kept straight after the move");

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(CivilianChunkLoading.held(id).contains(east), "released in the end"))
                .thenExecute(() -> {
                    long kept = level.getGameTime() - movedAt;
                    helper.assertTrue(kept >= LoadPlan.KEEP_TICKS, "kept for at least KEEP_TICKS, got " + kept);
                    helper.assertTrue(CivilianChunkLoading.held(id).equals(columns(centre, -1, 0)),
                            "now the staked part around the new chunk");
                    tearDown(helper, setup);
                })
                .thenSucceed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void aFrozenEmpireLoadsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setUp(helper, 640);
        UUID id = setup.empire().id();
        CivilianChunkLoading.recompute(level);
        helper.assertTrue(CivilianChunkLoading.held(id).size() == 9, "loads with a member");
        UUID founder = setup.empire().members().iterator().next();
        EmpireSavedData.of(level).removeMember(setup.empire(), founder);
        CivilianChunkLoading.recompute(level);
        helper.assertTrue(CivilianChunkLoading.held(id).isEmpty(), "a frozen empire holds nothing");
        tearDown(helper, setup);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void anInactiveEmpireLoadsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setUp(helper, 660);
        UUID id = setup.empire().id();
        EmpireRecord empire = setup.empire();
        empire.setLastMemberOnline(level.getGameTime() - LoadPlan.INACTIVE_TICKS - 1);
        CivilianChunkLoading.recompute(level);
        helper.assertTrue(CivilianChunkLoading.held(id).isEmpty(), "no member for over 5 server days: nothing held");
        empire.setLastMemberOnline(level.getGameTime() - LoadPlan.INACTIVE_TICKS + 100);
        CivilianChunkLoading.recompute(level);
        helper.assertTrue(CivilianChunkLoading.held(id).size() == 9, "within 5 server days: loads");
        tearDown(helper, setup);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = OFF_BATCH)
    public static void offLoadsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setUp(helper, 680);
        UUID id = setup.empire().id();
        try {
            CivilianChunkLoading.setModeOverride(LoadPlan.Mode.OFF);
            CivilianChunkLoading.recompute(level);
            helper.assertTrue(CivilianChunkLoading.held(id).isEmpty(), "OFF: nothing held");
            CivilianChunkLoading.setModeOverride(LoadPlan.Mode.MEMBER_ONLINE);
            CivilianChunkLoading.recompute(level);
            helper.assertTrue(CivilianChunkLoading.held(id).isEmpty(), "MEMBER_ONLINE with no member online: nothing held");
        } finally {
            CivilianChunkLoading.setModeOverride(null);
        }
        CivilianChunkLoading.recompute(level);
        helper.assertTrue(CivilianChunkLoading.held(id).size() == 9, "ALWAYS (the config): loads");
        tearDown(helper, setup);
        helper.succeed();
    }

    private CivilianLoadingGameTests() {
    }
}
