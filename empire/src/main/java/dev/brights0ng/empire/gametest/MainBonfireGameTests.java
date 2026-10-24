// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireBlockEntity;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.empire.rules.UnstakeResult;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Which bonfire is an empire's main one, where its core chunk is while it has none, what is protected, and which bonfire
 * may burn. Most work on the bonfire registry alone (no blocks), with empires far from every test structure.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class MainBonfireGameTests {

    /** A bonfire placed before its 3x3 is staked becomes the main one when the last of those chunks is staked. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void aBonfireBecomesMainOnceItsThreeByThreeIsStaked(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        EmpireRecord record = data.createEmpire(UUID.randomUUID(), ChunkKeys.pack(300, 300), null, 0L);
        record.state().setDominion(12);
        BlockPos pos = blockIn(301, 300);
        try {
            data.onBonfirePlaced(pos, server);
            helper.assertTrue(record.mainBonfire() == null, "a bonfire whose 3x3 is not all staked is not main");
            helper.assertTrue(EmpireSavedData.stakedAround(record, pos) == 6, "6 of its 9 chunks are staked");

            for (int dz = -1; dz <= 1; dz++) {
                StakeResult result = data.stakeChunk(record, ChunkKeys.pack(302, 300 + dz), 0L, true, server);
                helper.assertTrue(result == StakeResult.OK, "staking (302, " + (300 + dz) + "): " + result);
                if (dz < 1) {
                    helper.assertTrue(record.mainBonfire() == null, "not main before its 3x3 is complete");
                }
            }
            helper.assertTrue(pos.equals(record.mainBonfire()), "main once its 3x3 is staked: " + record.mainBonfire());
            helper.assertTrue(EmpireSavedData.coreChunk(record) == ChunkKeys.pack(301, 300), "the core moved with it");
        } finally {
            data.onBonfireRemoved(pos, null);
            data.disbandEmpire(record.id(), null);
        }
        helper.succeed();
    }

    /**
     * Losing the main bonfire keeps the core chunk on it, not the founding chunk, whose 3x3 may have been unstaked since,
     * and the core chunk survives a save and load. Without a main bonfire nothing is protected: the divine area is gone,
     * and only the core chunk itself cannot be unstaked.
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void theCoreStaysOnTheLastMainBonfireWhenItIsLost(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        BlockPos founding = blockIn(-300, 300);
        EmpireRecord record = data.createEmpire(UUID.randomUUID(), ChunkKeys.pack(-300, 300), founding, 0L);
        record.state().setDominion(13);
        BlockPos second = blockIn(-299, 300);
        try {
            for (int dz = -1; dz <= 1; dz++) {
                helper.assertTrue(data.stakeChunk(record, ChunkKeys.pack(-298, 300 + dz), 0L, true, server) == StakeResult.OK,
                        "staking (-298, " + (300 + dz) + ")");
            }
            data.onBonfirePlaced(second, server);
            helper.assertTrue(founding.equals(record.mainBonfire()), "the founding bonfire stays main");

            data.onBonfireRemoved(founding, null);
            helper.assertTrue(second.equals(record.mainBonfire()), "the second bonfire, whose 3x3 is staked, takes over");
            UnstakeResult freed = data.unstakeChunk(record, ChunkKeys.pack(-301, 300), 0L);
            helper.assertTrue(freed == UnstakeResult.OK, "the founding 3x3 is no longer divine: " + freed);

            data.onBonfireRemoved(second, null);
            helper.assertTrue(record.mainBonfire() == null, "no bonfire is left");
            helper.assertTrue(EmpireSavedData.coreChunk(record) == ChunkKeys.pack(-299, 300),
                    "the core stays on the last main bonfire's chunk");
            helper.assertTrue(data.getDivineOwner(ChunkKeys.pack(-299, 300)) == null
                    && data.getDivineOwner(ChunkKeys.pack(-298, 300)) == null, "without a main bonfire nothing is protected");
            helper.assertTrue(data.unstakeChunk(record, ChunkKeys.pack(-299, 300), 0L) == UnstakeResult.CORE_CHUNK,
                    "the core chunk still can't be unstaked");
            helper.assertTrue(data.unstakeChunk(record, ChunkKeys.pack(-298, 301), 0L) == UnstakeResult.OK,
                    "a chunk beside the core can be unstaked without a main bonfire");

            CompoundTag saved = data.save(new CompoundTag(), server.registryAccess());
            EmpireRecord loaded = EmpireSavedData.load(saved, server.registryAccess()).getEmpire(record.id());
            helper.assertTrue(loaded != null && loaded.coreChunk() == ChunkKeys.pack(-299, 300), "the core chunk is saved");
        } finally {
            data.disbandEmpire(record.id(), null);
        }
        helper.succeed();
    }

    /** With a main bonfire, protection covers its 3x3, and no other bonfire of the empire can be lit. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void onlyTheMainBonfireCanBeLitAndItAloneIsProtected(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        BlockPos main = blockIn(500, -300);
        EmpireRecord record = data.createEmpire(UUID.randomUUID(), ChunkKeys.pack(500, -300), main, 0L);
        BlockPos other = blockIn(501, -299);
        try {
            data.onBonfirePlaced(other, server);
            helper.assertTrue(data.getDivineOwner(ChunkKeys.pack(501, -299)) == record, "the main's 3x3 is protected");
            helper.assertTrue(BonfireBlock.canLight(helper.getLevel(), main, null), "the main bonfire can be lit");
            helper.assertFalse(BonfireBlock.canLight(helper.getLevel(), other, null), "another bonfire can't be lit");
            helper.assertTrue(main.equals(BonfireBlock.mainElsewhere(helper.getLevel(), other)), "its main is elsewhere");

            data.onBonfireRemoved(main, null);
            helper.assertTrue(record.mainBonfire() == null, "the other bonfire's 3x3 is not staked, so none is main");
            helper.assertTrue(BonfireBlock.canLight(helper.getLevel(), other, null), "with no main, a bonfire can be lit");
            helper.assertTrue(data.getDivineOwner(ChunkKeys.pack(500, -300)) == null, "and nothing is protected");
        } finally {
            data.onBonfireRemoved(other, null);
            data.disbandEmpire(record.id(), null);
        }
        helper.succeed();
    }

    /**
     * A lit bonfire in an empire's land goes out when another bonfire is the empire's main one (the block entity's check,
     * which its ticker runs every {@link BonfireBlockEntity#NOT_MAIN_CHECK_TICKS}).
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void aLitBonfireThatIsNotMainGoesOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level.getServer());
        BlockPos pos = new BlockPos(2408, 80, -2392);
        ChunkPos chunk = new ChunkPos(pos);
        level.setChunkForced(chunk.x, chunk.z, true);
        // The main bonfire is only a registry entry a few blocks away; the one placed here is not main.
        BlockPos main = pos.offset(3, 0, 3);
        EmpireRecord record = data.createEmpire(UUID.randomUUID(), ChunkKeys.pack(chunk.x, chunk.z), main, level.getGameTime());
        level.setBlock(pos, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true), 3);
        try {
            BonfireBlockEntity be = (BonfireBlockEntity) level.getBlockEntity(pos);
            helper.assertTrue(be != null, "the bonfire has its block entity");
            helper.assertTrue(BonfireBlockEntity.putOutIfNotMain(level, pos, level.getBlockState(pos), be), "it was put out");
            helper.assertFalse(level.getBlockState(pos).getValue(BonfireBlock.LIT), "the non-main bonfire is out");

            record.setMainBonfire(pos);
            level.setBlock(pos, level.getBlockState(pos).setValue(BonfireBlock.LIT, true), 3);
            helper.assertFalse(BonfireBlockEntity.putOutIfNotMain(level, pos, level.getBlockState(pos), be),
                    "the main bonfire keeps burning");
        } finally {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            data.onBonfireRemoved(main, null);
            data.disbandEmpire(record.id(), null);
            level.setChunkForced(chunk.x, chunk.z, false);
        }
        helper.succeed();
    }

    private static BlockPos blockIn(int chunkX, int chunkZ) {
        return new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
    }

    private MainBonfireGameTests() {
    }
}
