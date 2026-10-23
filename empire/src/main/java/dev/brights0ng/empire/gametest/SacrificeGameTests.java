// Written by Gemini; moved from bread to god sacrifices (founding meat, paying the demand) by Claude; members edited by Claude.
package dev.brights0ng.empire.gametest;

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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class SacrificeGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = GameTestStructures.EMPTY)
    public static void foundingSetsDominion10Stakes9Available1(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runFoundingSetsDominion10Stakes9Available1(helper);
        } finally {
            restoreTierChecks();
        }
    }

    private static void runFoundingSetsDominion10Stakes9Available1(GameTestHelper helper) {
        clearArea(helper, SPOT);
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true));
        BonfireItem.placeParts(helper.getLevel(), helper.absolutePos(SPOT));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));

        interactWithBonfire(helper, SPOT, player);

        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 0,
                "5 beef consumed on successful founding");

        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        EmpireRecord record = data.getEmpireOf(player.getUUID());
        helper.assertTrue(record != null, "Empire created for founder");
        helper.assertTrue(record.state().dominion() == 10, "Founding dominion is 10");
        helper.assertTrue(record.state().stakedCount() == 9, "9 chunks staked at founding");

        long day = DawnClock.dayOf(helper.getLevel().getDayTime());
        helper.assertTrue(record.state().available(day) == 1, "1 dominion point available at founding");
        helper.assertTrue(helper.absolutePos(SPOT).equals(record.mainBonfire()), "Main bonfire is set to founding bonfire");

        // Verify chunk index holds all 9 chunks
        ChunkPos centerPos = new ChunkPos(helper.absolutePos(SPOT));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long c = ChunkKeys.pack(centerPos.x + dx, centerPos.z + dz);
                helper.assertTrue(record.id().equals(data.getEmpireByChunk(c).id()),
                        "Chunk [" + (centerPos.x + dx) + ", " + (centerPos.z + dz) + "] in chunk index");
            }
        }

        data.disbandEmpire(record.id(), server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void secondSacrificeIncreasesDominionTo15(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runSecondSacrificeIncreasesDominionTo15(helper);
        } finally {
            restoreTierChecks();
        }
    }

    private static void runSecondSacrificeIncreasesDominionTo15(GameTestHelper helper) {
        clearArea(helper, SPOT);
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true));
        BonfireItem.placeParts(helper.getLevel(), helper.absolutePos(SPOT));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 10));

        // 1. Founding sacrifice
        interactWithBonfire(helper, SPOT, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 5,
                "5 beef consumed for first sacrifice (founding)");

        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        EmpireRecord record = data.getEmpireOf(player.getUUID());
        helper.assertTrue(record != null, "Empire exists");

        // 2. Paying the god's first demand in full on the main bonfire
        GodSacrificeGameTests.payInFull(helper, helper.getLevel(), helper.absolutePos(SPOT), player, record);
        helper.assertTrue(record.state().dominion() == 15, "Dominion increased from 10 to 15");
        helper.assertTrue(record.state().stakedCount() == 9, "Staked chunks remain 9");

        long day = DawnClock.dayOf(helper.getLevel().getDayTime());
        helper.assertTrue(record.state().available(day) == 6, "Available dominion is now 6 (15 - 9)");

        data.disbandEmpire(record.id(), server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void sacrificeRefusedUnderInvalidConditions(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runSacrificeRefusedUnderInvalidConditions(helper);
        } finally {
            restoreTierChecks();
        }
    }

    private static void runSacrificeRefusedUnderInvalidConditions(GameTestHelper helper) {
        clearArea(helper, SPOT);
        BlockPos mainPos = helper.absolutePos(SPOT);
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);

        // Sub-case A: Unlit bonfire
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, false));
        BonfireItem.placeParts(helper.getLevel(), mainPos);

        Player player1 = helper.makeMockPlayer(GameType.SURVIVAL);
        player1.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
        interactWithBonfire(helper, SPOT, player1);

        helper.assertTrue(player1.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 5,
                "Beef not consumed when bonfire is unlit");
        helper.assertTrue(data.getEmpireOf(player1.getUUID()) == null,
                "Empire not founded when bonfire is unlit");

        // Sub-case B: < 5 beef
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true));
        player1.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 4));
        interactWithBonfire(helper, SPOT, player1);

        helper.assertTrue(player1.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 4,
                "Beef not consumed when count < 5");
        helper.assertTrue(data.getEmpireOf(player1.getUUID()) == null,
                "Empire not founded with < 5 beef");

        // Now found empire legitimately with player1
        player1.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
        interactWithBonfire(helper, SPOT, player1);
        EmpireRecord record = data.getEmpireOf(player1.getUUID());
        helper.assertTrue(record != null, "Empire founded");

        // Sub-case C: Non-member on claimed bonfire offering beef the god's demand asks for: it is a join offering, and 4
        // is too few, so nothing is paid
        Player player2 = helper.makeMockPlayer(GameType.SURVIVAL);
        player2.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 4));
        interactWithBonfire(helper, SPOT, player2);

        helper.assertTrue(player2.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 4,
                "Beef not consumed when a non-member offers sacrifice");
        helper.assertTrue(record.state().dominion() == 10 && !record.isMember(player2.getUUID()),
                "Dominion unchanged and no new member when a non-member offers too little");

        // Sub-case D: Not main bonfire (secondary bonfire placed in empire land)
        BlockPos secondaryPos = mainPos.offset(5, 0, 0);
        helper.getLevel().setBlock(secondaryPos, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true), 3);
        data.onBonfirePlaced(secondaryPos, server);

        player1.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
        interactWithAbsolutePos(helper.getLevel(), secondaryPos, player1);

        helper.assertTrue(player1.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 5,
                "Beef not consumed when sacrifice offered at secondary (non-main) bonfire");
        helper.assertTrue(record.state().dominion() == 10,
                "Dominion unchanged at secondary bonfire");

        data.onBonfireRemoved(secondaryPos, server);
        data.disbandEmpire(record.id(), server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void founderCannotFoundTwice(GameTestHelper helper) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            runFounderCannotFoundTwice(helper);
        } finally {
            restoreTierChecks();
        }
    }

    private static void runFounderCannotFoundTwice(GameTestHelper helper) {
        clearArea(helper, SPOT);
        BlockPos mainPos1 = helper.absolutePos(SPOT);
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true));
        BonfireItem.placeParts(helper.getLevel(), mainPos1);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 10));

        interactWithBonfire(helper, SPOT, player);

        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        EmpireRecord record = data.getEmpireOf(player.getUUID());
        helper.assertTrue(record != null, "First empire founded");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 5, "5 beef remaining");

        // Second unclaimed bonfire at a distant chunk (e.g. 100 chunks away)
        BlockPos distantPos = new BlockPos(1600, 64, 1600);
        ChunkPos distantChunkPos = new ChunkPos(distantPos);
        long distantChunk = ChunkKeys.pack(distantChunkPos.x, distantChunkPos.z);
        server.overworld().setBlock(distantPos, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true), 3);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
        interactWithAbsolutePos(server.overworld(), distantPos, player);

        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 5,
                "Beef not consumed when founder tries to found a second empire");
        helper.assertTrue(data.getEmpireByChunk(distantChunk) == null,
                "Distant chunk remains unclaimed; second founding refused");

        data.disbandEmpire(record.id(), server);
        server.overworld().setBlock(distantPos, Blocks.AIR.defaultBlockState(), 3);
        helper.succeed();
    }

    private static void interactWithBonfire(GameTestHelper helper, BlockPos spot, Player player) {
        BlockPos absolutePos = helper.absolutePos(spot);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolutePos), Direction.UP, absolutePos, false);
        helper.getBlockState(spot).useItemOn(player.getItemInHand(InteractionHand.MAIN_HAND), helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
    }

    private static void interactWithAbsolutePos(Level level, BlockPos pos, Player player) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        level.getBlockState(pos).useItemOn(player.getItemInHand(InteractionHand.MAIN_HAND), level, player, InteractionHand.MAIN_HAND, hit);
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
