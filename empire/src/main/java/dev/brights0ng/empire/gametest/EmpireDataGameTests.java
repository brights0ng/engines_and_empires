// Written by Gemini; the divine area edited by Claude.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.compat.frontier.FrontierCompat;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.empire.rules.UnstakeResult;
import dev.brights0ng.empire.tier.TierCheck;
import dev.brights0ng.empire.tier.TierChecks;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class EmpireDataGameTests {

    @GameTest(template = GameTestStructures.EMPTY)
    public static void saveLoadRoundTrip(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = new EmpireSavedData();

        UUID founderId = UUID.randomUUID();
        long centerChunk = ChunkKeys.pack(10, 20);
        BlockPos mainBonfire = new BlockPos(165, 64, 325);
        BlockPos secondaryBonfire = new BlockPos(170, 64, 330);

        EmpireRecord record = data.createEmpire(founderId, centerChunk, mainBonfire, 1000L);
        data.onBonfirePlaced(secondaryBonfire, server);

        // Stake an adjacent chunk (12, 20)
        long adjacentChunk = ChunkKeys.pack(12, 20);
        StakeResult stakeRes = data.stakeChunk(record, adjacentChunk, 0, true);
        helper.assertTrue(stakeRes == StakeResult.OK, "Adjacent chunk staked");

        // Stake (13, 20) past it, then unstake it, creating a dormant point (the 3x3 around the core is divine land)
        record.state().addDominion(1);
        long cornerChunk = ChunkKeys.pack(13, 20);
        helper.assertTrue(data.stakeChunk(record, cornerChunk, 0, true) == StakeResult.OK, "Outer chunk staked");
        UnstakeResult unstakeRes = data.unstakeChunk(record, cornerChunk, 0);
        helper.assertTrue(unstakeRes == UnstakeResult.OK, "Outer chunk unstaked");

        // Save
        CompoundTag tag = data.save(new CompoundTag(), server.registryAccess());

        // Load
        EmpireSavedData loaded = EmpireSavedData.load(tag, server.registryAccess());

        // Verify loaded data
        EmpireRecord loadedRecord = loaded.getEmpire(record.id());
        helper.assertTrue(loadedRecord != null, "Empire record found after load");
        helper.assertTrue(founderId.equals(loadedRecord.founder()), "Founder UUID matches");
        helper.assertTrue(loadedRecord.foundedGameTime() == 1000L, "Founded game time matches");
        helper.assertTrue(mainBonfire.equals(loadedRecord.mainBonfire()), "Main bonfire position matches");
        helper.assertTrue(loadedRecord.state().dominion() == 11, "Dominion matches (10 founding + 1 added)");
        helper.assertTrue(loadedRecord.state().foundingChunk() == centerChunk, "Founding chunk matches");

        // Staked chunks
        helper.assertTrue(loadedRecord.state().stakedCount() == 10, "10 chunks staked (9 founding + 1 adjacent)");
        helper.assertTrue(loadedRecord.state().staked().contains(adjacentChunk), "Adjacent chunk in staked set");
        helper.assertTrue(!loadedRecord.state().staked().contains(cornerChunk), "Unstaked chunk not in staked set");

        // Dormant points
        helper.assertTrue(loadedRecord.state().dormant().size() == 1, "1 dormant point");
        helper.assertTrue(loadedRecord.state().dormant().get(0).availableOnDay() == 1, "Dormant point available on day 1");

        // Rebuilt indices
        helper.assertTrue(record.id().equals(loaded.getEmpireByFounder(founderId).id()), "Founder index rebuilt");
        helper.assertTrue(record.id().equals(loaded.getEmpireByChunk(adjacentChunk).id()), "Chunk index rebuilt for adjacent");
        helper.assertTrue(loaded.getEmpireByChunk(cornerChunk) == null, "Unstaked chunk not in chunk index");

        // Bonfires registry
        helper.assertTrue(loaded.bonfires().contains(mainBonfire), "Main bonfire in registry");
        helper.assertTrue(loaded.bonfires().contains(secondaryBonfire), "Secondary bonfire in registry");
        helper.assertTrue(loaded.bonfires().size() == 2, "Bonfire registry has size 2");

        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void debugStakeUnstakeObeyRules(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runDebugStakeUnstakeObeyRules(helper);
        } finally {
            if (ModList.get().isLoaded("frontier")) {
                FrontierCompat.registerIfLoaded();
            } else {
                TierChecks.reset();
            }
        }
    }

    private static void runDebugStakeUnstakeObeyRules(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);

        String playerName = "founder_rules_" + helper.getLevel().random.nextInt(10000);
        UUID founderId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Found empire at test location chunk
        BlockPos testPos = helper.absolutePos(new BlockPos(0, 1, 0));
        int cx = testPos.getX() >> 4;
        int cz = testPos.getZ() >> 4;
        long centerChunk = ChunkKeys.pack(cx, cz);
        EmpireRecord record = data.createEmpire(founderId, centerChunk, testPos, server.overworld().getGameTime());

        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();

        // 1. Stake already staked chunk (center) -> ALREADY_OURS
        CommandSourceStack centerSource = server.createCommandSourceStack()
                .withPermission(2)
                .withPosition(Vec3.atCenterOf(testPos))
                .withLevel(helper.getLevel());

        try {
            dispatcher.execute("empire debug stake " + playerName, centerSource);
        } catch (Exception e) {
            helper.fail("Command execution failed: " + e.getMessage());
        }

        // 2. Stake distant chunk -> NOT_ADJACENT
        BlockPos distantPos = testPos.offset(160, 0, 160);
        CommandSourceStack distantSource = server.createCommandSourceStack()
                .withPermission(2)
                .withPosition(Vec3.atCenterOf(distantPos))
                .withLevel(helper.getLevel());

        try {
            dispatcher.execute("empire debug stake " + playerName, distantSource);
        } catch (Exception e) {
            helper.fail("Command execution failed: " + e.getMessage());
        }
        helper.assertTrue(!data.isChunkStaked(ChunkKeys.pack(distantPos.getX() >> 4, distantPos.getZ() >> 4)),
                "Distant chunk should not be staked");

        // 3. Stake adjacent chunk -> OK
        BlockPos adjacentPos = testPos.offset(32, 0, 0); // 2 chunks away in X, adjacent to (cx + 1, cz)
        int adjCx = adjacentPos.getX() >> 4;
        int adjCz = adjacentPos.getZ() >> 4;
        long adjChunk = ChunkKeys.pack(adjCx, adjCz);
        CommandSourceStack adjacentSource = server.createCommandSourceStack()
                .withPermission(2)
                .withPosition(Vec3.atCenterOf(adjacentPos))
                .withLevel(helper.getLevel());

        try {
            dispatcher.execute("empire debug stake " + playerName, adjacentSource);
        } catch (Exception e) {
            helper.fail("Command execution failed: " + e.getMessage());
        }
        helper.assertTrue(data.isChunkStaked(adjChunk), "Adjacent chunk staked successfully");
        helper.assertTrue(record.state().available(0) == 0, "No available dominion points remain");

        // 4. Stake another adjacent chunk -> NO_DOMINION
        BlockPos anotherAdjPos = testPos.offset(-32, 0, 0);
        long anotherAdjChunk = ChunkKeys.pack(anotherAdjPos.getX() >> 4, anotherAdjPos.getZ() >> 4);
        CommandSourceStack anotherAdjSource = server.createCommandSourceStack()
                .withPermission(2)
                .withPosition(Vec3.atCenterOf(anotherAdjPos))
                .withLevel(helper.getLevel());

        try {
            dispatcher.execute("empire debug stake " + playerName, anotherAdjSource);
        } catch (Exception e) {
            helper.fail("Command execution failed: " + e.getMessage());
        }
        helper.assertTrue(!data.isChunkStaked(anotherAdjChunk), "Chunk should not stake with 0 available dominion");

        // 5. Unstake core chunk -> refused (CORE_CHUNK)
        try {
            dispatcher.execute("empire debug unstake " + playerName, centerSource);
        } catch (Exception e) {
            helper.fail("Command execution failed: " + e.getMessage());
        }
        helper.assertTrue(data.isChunkStaked(centerChunk), "Core chunk cannot be unstaked");

        // 6. Unstake adjacent chunk -> OK
        try {
            dispatcher.execute("empire debug unstake " + playerName, adjacentSource);
        } catch (Exception e) {
            helper.fail("Command execution failed: " + e.getMessage());
        }
        helper.assertTrue(!data.isChunkStaked(adjChunk), "Adjacent chunk unstaked successfully");
        helper.assertTrue(record.state().dormant().size() == 1, "Freed point is dormant");

        // Clean up
        data.disbandEmpire(record.id(), server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void disbandFreesChunks(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);

        String playerName = "founder_disband_" + helper.getLevel().random.nextInt(10000);
        UUID founderId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        BlockPos testPos = helper.absolutePos(new BlockPos(0, 1, 0));
        long centerChunk = ChunkKeys.pack(testPos.getX() >> 4, testPos.getZ() >> 4);
        EmpireRecord record = data.createEmpire(founderId, centerChunk, testPos, server.overworld().getGameTime());

        helper.assertTrue(data.isChunkStaked(centerChunk), "Chunk is staked initially");
        helper.assertTrue(data.getEmpireByFounder(founderId) != null, "Empire exists before disband");

        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack op = server.createCommandSourceStack()
                .withPermission(2)
                .withPosition(Vec3.atCenterOf(testPos))
                .withLevel(helper.getLevel());

        try {
            dispatcher.execute("empire debug disband " + playerName, op);
        } catch (Exception e) {
            helper.fail("Disband command failed: " + e.getMessage());
        }

        helper.assertTrue(data.getEmpireByFounder(founderId) == null, "Empire removed after disband");
        helper.assertTrue(data.getEmpire(record.id()) == null, "Record removed after disband");

        // Verify all 9 chunks are freed
        int cx = ChunkKeys.getX(centerChunk);
        int cz = ChunkKeys.getZ(centerChunk);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long c = ChunkKeys.pack(cx + dx, cz + dz);
                helper.assertTrue(!data.isChunkStaked(c), "Chunk [" + (cx + dx) + ", " + (cz + dz) + "] freed");
            }
        }

        helper.succeed();
    }
}
