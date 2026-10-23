// Written by Gemini; members and the divine area edited by Claude.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireItem;
import dev.brights0ng.empire.compat.frontier.FrontierCompat;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.tier.TierCheck;
import dev.brights0ng.empire.tier.TierChecks;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class TalcGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = GameTestStructures.EMPTY)
    public static void stakeEndToEndResults(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runStakeEndToEndResults(helper);
        } finally {
            restoreTierChecks();
        }
    }

    private static void runStakeEndToEndResults(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        UUID founder = player.getUUID();

        BlockPos centerPos = helper.absolutePos(SPOT);
        ChunkPos centerChunkPos = new ChunkPos(centerPos);
        long centerChunk = ChunkKeys.pack(centerChunkPos.x, centerChunkPos.z);

        EmpireRecord record = data.createEmpire(founder, centerChunk, centerPos, helper.getLevel().getGameTime());

        // 1. ALREADY_OURS: click center chunk
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.BLESSED_TALC.get(), 10));
        InteractionResult resAlready = useBlessedTalc(player, centerPos);
        helper.assertTrue(resAlready == InteractionResult.FAIL, "Staking already owned chunk should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 10, "Nothing consumed on ALREADY_OURS");

        // 2. NOT_ADJACENT: chunk 5 away in X
        BlockPos distantPos = new BlockPos((centerChunkPos.x + 5) << 4, centerPos.getY(), (centerChunkPos.z) << 4);
        InteractionResult resNotAdj = useBlessedTalc(player, distantPos);
        helper.assertTrue(resNotAdj == InteractionResult.FAIL, "Staking non-adjacent chunk should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 10, "Nothing consumed on NOT_ADJACENT");

        // 3. OK: adjacent chunk (centerChunkPos.x + 2, centerChunkPos.z) since founding claimed [-1, 0, 1] relative
        BlockPos adjPos = new BlockPos((centerChunkPos.x + 2) << 4, centerPos.getY(), (centerChunkPos.z) << 4);
        ChunkPos adjChunkPos = new ChunkPos(adjPos);
        long adjChunk = ChunkKeys.pack(adjChunkPos.x, adjChunkPos.z);
        InteractionResult resOk = useBlessedTalc(player, adjPos);
        helper.assertTrue(resOk == InteractionResult.SUCCESS, "Staking valid adjacent chunk should succeed");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 9, "1 Blessed Talc consumed on OK");
        helper.assertTrue(record.equals(data.getEmpireByChunk(adjChunk)), "Chunk index updated with new stake");
        helper.assertTrue(record.state().staked().contains(adjChunk), "EmpireState staked set contains new chunk");

        // 4. NO_DOMINION: now staked = 10 (9 initial + 1), dominion = 10, available = 0
        BlockPos nextAdjPos = new BlockPos((centerChunkPos.x + 3) << 4, centerPos.getY(), (centerChunkPos.z) << 4);
        InteractionResult resNoDom = useBlessedTalc(player, nextAdjPos);
        helper.assertTrue(resNoDom == InteractionResult.FAIL, "Staking with 0 available dominion should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 9, "Nothing consumed on NO_DOMINION");

        // 5. OWNED_BY_OTHER: another empire claims nextAdjPos
        UUID otherFounder = UUID.randomUUID();
        long nextAdjChunk = ChunkKeys.pack(nextAdjPos.getX() >> 4, nextAdjPos.getZ() >> 4);
        EmpireRecord otherEmpire = data.createEmpire(otherFounder, nextAdjChunk, null, helper.getLevel().getGameTime());
        // Add dominion so our empire could afford it if not owned
        record.state().addDominion(5);
        InteractionResult resOwned = useBlessedTalc(player, nextAdjPos);
        helper.assertTrue(resOwned == InteractionResult.FAIL, "Staking chunk owned by another empire should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 9, "Nothing consumed on OWNED_BY_OTHER");

        // 6. NOT_SETTLED: install TierCheck that returns false
        TierChecks.install(() -> (lvl, chk) -> false);
        BlockPos settledFailPos = new BlockPos((centerChunkPos.x - 2) << 4, centerPos.getY(), (centerChunkPos.z) << 4);
        InteractionResult resNotSettled = useBlessedTalc(player, settledFailPos);
        helper.assertTrue(resNotSettled == InteractionResult.FAIL, "Staking unsettled chunk should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 9, "Nothing consumed on NOT_SETTLED");

        data.disbandEmpire(record.id(), server);
        data.disbandEmpire(otherEmpire.id(), server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void unstakeEndToEndResults(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runUnstakeEndToEndResults(helper);
        } finally {
            restoreTierChecks();
        }
    }

    private static void runUnstakeEndToEndResults(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        UUID founder = player.getUUID();

        BlockPos centerPos = helper.absolutePos(SPOT);
        ChunkPos centerChunkPos = new ChunkPos(centerPos);
        long centerChunk = ChunkKeys.pack(centerChunkPos.x, centerChunkPos.z);

        EmpireRecord record = data.createEmpire(founder, centerChunk, centerPos, helper.getLevel().getGameTime());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.TARNISHED_TALC.get(), 10));

        // 1. NOT_OURS: click chunk not in empire
        BlockPos unownedPos = new BlockPos((centerChunkPos.x + 5) << 4, centerPos.getY(), (centerChunkPos.z) << 4);
        InteractionResult resNotOurs = useTarnishedTalc(player, unownedPos);
        helper.assertTrue(resNotOurs == InteractionResult.FAIL, "Unstaking unowned chunk should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 10, "Nothing consumed on NOT_OURS");

        // 2. CORE_CHUNK: center chunk with main bonfire
        InteractionResult resCore = useTarnishedTalc(player, centerPos);
        helper.assertTrue(resCore == InteractionResult.FAIL, "Unstaking core chunk should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 10, "Nothing consumed on CORE_CHUNK");

        // 3. DIVINE_CHUNK: a chunk of the 3x3 around the core
        BlockPos divinePos = new BlockPos((centerChunkPos.x + 1) << 4, centerPos.getY(), (centerChunkPos.z + 1) << 4);
        InteractionResult resDivine = useTarnishedTalc(player, divinePos);
        helper.assertTrue(resDivine == InteractionResult.FAIL, "Unstaking a divine chunk should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 10, "Nothing consumed on DIVINE_CHUNK");

        // 4. BRIDGE_CHUNK: a line out of the divine area, center+2 -> center+3; center+2 joins center+3 to the rest
        long day = DawnClock.dayOf(helper.getLevel().getDayTime());
        record.state().addDominion(10);
        data.stakeChunk(record, ChunkKeys.pack(centerChunkPos.x + 2, centerChunkPos.z), day, true);
        data.stakeChunk(record, ChunkKeys.pack(centerChunkPos.x + 3, centerChunkPos.z), day, true);

        BlockPos bridgePos = new BlockPos((centerChunkPos.x + 2) << 4, centerPos.getY(), (centerChunkPos.z) << 4);
        InteractionResult resBridge = useTarnishedTalc(player, bridgePos);
        helper.assertTrue(resBridge == InteractionResult.FAIL, "Unstaking bridge chunk should fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 10, "Nothing consumed on BRIDGE_CHUNK");

        // 5. OK: the leaf chunk (center+3, 0)
        BlockPos leafPos = new BlockPos((centerChunkPos.x + 3) << 4, centerPos.getY(), (centerChunkPos.z) << 4);
        long leafChunk = ChunkKeys.pack(centerChunkPos.x + 3, centerChunkPos.z);
        InteractionResult resOk = useTarnishedTalc(player, leafPos);
        helper.assertTrue(resOk == InteractionResult.SUCCESS, "Unstaking non-bridge leaf chunk should succeed");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 9, "1 Tarnished Talc consumed on OK");
        helper.assertTrue(data.getEmpireByChunk(leafChunk) == null, "Chunk index cleared on unstake");
        helper.assertFalse(record.state().staked().contains(leafChunk), "EmpireState staked removed on unstake");
        helper.assertTrue(record.state().dormant().size() == 1, "Point became dormant on unstake");

        data.disbandEmpire(record.id(), server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void dormantPointUnavailableUntilDawn(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runDormantPointUnavailableUntilDawn(helper);
        } finally {
            restoreTierChecks();
        }
    }

    private static void runDormantPointUnavailableUntilDawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        UUID founder = player.getUUID();

        BlockPos centerPos = helper.absolutePos(SPOT);
        ChunkPos centerChunkPos = new ChunkPos(centerPos);
        long centerChunk = ChunkKeys.pack(centerChunkPos.x, centerChunkPos.z);

        // Set day time to noon on day 0
        server.overworld().setDayTime(6000L);
        long day0 = DawnClock.dayOf(server.overworld().getDayTime());
        helper.assertTrue(day0 == 0L, "World time should be on day 0");

        EmpireRecord record = data.createEmpire(founder, centerChunk, centerPos, server.overworld().getGameTime());

        // Initial: D=10, 9 staked, 1 available
        helper.assertTrue(record.state().available(day0) == 1, "Initially 1 available dominion point");

        // Stake 1 adjacent chunk -> D=10, 10 staked, 0 available
        BlockPos adjPos = new BlockPos((centerChunkPos.x + 2) << 4, centerPos.getY(), (centerChunkPos.z) << 4);
        data.stakeChunk(record, ChunkKeys.pack(adjPos.getX() >> 4, adjPos.getZ() >> 4), day0, true);
        helper.assertTrue(record.state().available(day0) == 0, "After staking, 0 available dominion");

        // Unstake that chunk on day 0 -> point becomes dormant until day 1
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.TARNISHED_TALC.get(), 1));
        InteractionResult res = useTarnishedTalc(player, adjPos);
        helper.assertTrue(res == InteractionResult.SUCCESS, "Unstaking succeeds");
        helper.assertTrue(record.state().dormant().size() == 1, "1 dormant point created");

        // Still day 0: point is NOT available
        helper.assertTrue(record.state().available(day0) == 0, "Dormant point is not available on day 0");

        // Advance time past dawn (day 1, e.g. tick 24001)
        server.overworld().setDayTime(24001L);
        long day1 = DawnClock.dayOf(server.overworld().getDayTime());
        helper.assertTrue(day1 == 1L, "World time rolled over to day 1");

        // Now point is released and available
        helper.assertTrue(record.state().available(day1) == 1, "Dormant point released at dawn of day 1");
        helper.assertTrue(record.state().dormant().isEmpty(), "Dormant list cleared after dawn release");

        data.disbandEmpire(record.id(), server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void blessingNeedsLitMainBonfire(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runBlessingNeedsLitMainBonfire(helper);
        } finally {
            restoreTierChecks();
        }
    }

    private static void runBlessingNeedsLitMainBonfire(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);

        clearArea(helper, SPOT);
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true));
        BonfireItem.placeParts(helper.getLevel(), helper.absolutePos(SPOT));

        Player founderPlayer = helper.makeMockPlayer(GameType.SURVIVAL);
        UUID founder = founderPlayer.getUUID();
        BlockPos bonfirePos = helper.absolutePos(SPOT);
        ChunkPos chunkPos = new ChunkPos(bonfirePos);

        EmpireRecord record = data.createEmpire(founder, ChunkKeys.pack(chunkPos.x, chunkPos.z), bonfirePos, server.overworld().getGameTime());

        // 1. Success: founder on lit main bonfire
        founderPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.RAW_TALC.get(), 3));
        interactWithBonfire(helper, SPOT, founderPlayer);
        helper.assertTrue(founderPlayer.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 2,
                "1 Raw Talc consumed on successful blessing");
        helper.assertTrue(founderPlayer.getInventory().contains(new ItemStack(EmpireContent.BLESSED_TALC.get())),
                "Player received Blessed Talc");

        // 2. Unlit bonfire fails
        helper.setBlock(SPOT, helper.getBlockState(SPOT).setValue(BonfireBlock.LIT, false).setValue(BonfireBlock.ROARING, false));
        founderPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.RAW_TALC.get(), 2));
        interactWithBonfire(helper, SPOT, founderPlayer);
        helper.assertTrue(founderPlayer.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 2,
                "Nothing consumed on unlit bonfire");

        // Relight
        helper.setBlock(SPOT, helper.getBlockState(SPOT).setValue(BonfireBlock.LIT, true));

        // 3. Non-member fails
        Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);
        stranger.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.RAW_TALC.get(), 2));
        interactWithBonfire(helper, SPOT, stranger);
        helper.assertTrue(stranger.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 2,
                "Nothing consumed when a non-member attempts blessing");

        // 4. Secondary bonfire (not main) fails
        BlockPos secondaryPos = SPOT.offset(5, 0, 5);
        clearArea(helper, secondaryPos);
        helper.setBlock(secondaryPos, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true));
        BonfireItem.placeParts(helper.getLevel(), helper.absolutePos(secondaryPos));

        founderPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.RAW_TALC.get(), 2));
        interactWithBonfire(helper, secondaryPos, founderPlayer);
        helper.assertTrue(founderPlayer.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 2,
                "Nothing consumed on secondary bonfire (not main)");

        data.disbandEmpire(record.id(), server);
        clearArea(helper, secondaryPos);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void tarnishingAtSoulCampfireLeavesCookingSlotsUntouched(GameTestHelper helper) {
        clearArea(helper, SPOT);
        helper.setBlock(SPOT, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.RAW_TALC.get(), 5));

        BlockPos absolutePos = helper.absolutePos(SPOT);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolutePos), Direction.UP, absolutePos, false);

        // Right click soul campfire with Raw Talc
        CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, absolutePos, hit);

        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 4,
                "1 Raw Talc consumed on successful tarnishing");
        helper.assertTrue(player.getInventory().contains(new ItemStack(EmpireContent.TARNISHED_TALC.get())),
                "Player received Tarnished Talc");

        // Verify cooking slots in soul campfire are untouched!
        if (helper.getLevel().getBlockEntity(absolutePos) instanceof CampfireBlockEntity campfire) {
            NonNullList<ItemStack> items = campfire.getItems();
            for (int i = 0; i < items.size(); i++) {
                helper.assertTrue(items.get(i).isEmpty(), "Campfire slot " + i + " must remain empty!");
            }
        }

        // Unlit soul campfire fails
        helper.setBlock(SPOT, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.RAW_TALC.get(), 4));
        CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, absolutePos, hit);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 4,
                "Nothing consumed when soul campfire is unlit");

        helper.succeed();
    }

    private static InteractionResult useBlessedTalc(Player player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND, hit);
        return player.getItemInHand(InteractionHand.MAIN_HAND).useOn(context);
    }

    private static InteractionResult useTarnishedTalc(Player player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND, hit);
        return player.getItemInHand(InteractionHand.MAIN_HAND).useOn(context);
    }

    private static void interactWithBonfire(GameTestHelper helper, BlockPos spot, Player player) {
        BlockPos absolutePos = helper.absolutePos(spot);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolutePos), Direction.UP, absolutePos, false);
        helper.getBlockState(spot).useItemOn(player.getItemInHand(InteractionHand.MAIN_HAND), helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
    }

    private static void restoreTierChecks() {
        if (ModList.get().isLoaded("frontier")) {
            FrontierCompat.registerIfLoaded();
        } else {
            TierChecks.reset();
        }
    }

    private static void clearArea(GameTestHelper helper, BlockPos center) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.setBlock(center.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
    }
}
