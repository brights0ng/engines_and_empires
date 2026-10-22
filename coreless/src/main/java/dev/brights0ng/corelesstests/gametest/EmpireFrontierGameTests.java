// Written by Gemini.
package dev.brights0ng.corelesstests.gametest;

import java.util.UUID;

import dev.brights0ng.corelesstests.CorelessTests;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.compat.frontier.FrontierTierCheck;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.frontier.tier.FrontierLevel;
import dev.brights0ng.frontier.tier.FrontierLevels;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CorelessTests.MODID)
@PrefixGameTestTemplate(false)
public final class EmpireFrontierGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = GameTestStructures.EMPTY, batch = "empire_frontier_settled")
    public static void frontierSettledCheckBlocksStakingInUninhabitedAndPassesInSettled(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        FrontierLevel frontier = FrontierLevels.of(helper.getLevel());

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        UUID founder = player.getUUID();

        BlockPos centerPos = helper.absolutePos(SPOT);
        ChunkPos centerChunkPos = new ChunkPos(centerPos);
        long centerChunk = ChunkKeys.pack(centerChunkPos.x, centerChunkPos.z);

        EmpireRecord record = data.createEmpire(founder, centerChunk, centerPos, helper.getLevel().getGameTime());

        // Target chunk is adjacent to empire's 3x3 footprint
        BlockPos targetPos = new BlockPos((centerChunkPos.x + 2) << 4, 64, (centerChunkPos.z) << 4);
        ChunkPos targetChunk = new ChunkPos(targetPos);
        FrontierTierCheck tierCheck = new FrontierTierCheck();

        // 1. In uninhabited land without anchors or habitation:
        frontier.clearHabitation(targetPos, 8);
        frontier.refreshAround(targetPos, 4);

        helper.assertFalse(tierCheck.columnSettled(helper.getLevel(), targetChunk),
                "Uninhabited land must not be settled");

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.BLESSED_TALC.get(), 1));
        InteractionResult resBlocked = useBlessedTalc(player, targetPos);
        helper.assertTrue(resBlocked == InteractionResult.FAIL,
                "Staking in uninhabited land must fail");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 1,
                "Blessed talc must not be consumed when staking in uninhabited land");
        helper.assertTrue(data.getEmpireByChunk(ChunkKeys.pack(targetChunk.x, targetChunk.z)) == null,
                "Chunk must not be claimed");

        // 2. Now settle the land: place composter workstation + villager above y 0
        helper.getLevel().setBlock(targetPos, Blocks.COMPOSTER.defaultBlockState(), 3);
        Villager villager = helper.spawn(EntityType.VILLAGER, SPOT);
        villager.setPos(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
        frontier.addHabitation(targetPos, frontier.params().settleTicks() * 2);
        frontier.refreshAround(targetPos, 4);

        helper.assertTrue(tierCheck.columnSettled(helper.getLevel(), targetChunk),
                "Land with workstation, villager, and habitation must be Settled");

        // 3. Staking in now-Settled land succeeds:
        InteractionResult resSuccess = useBlessedTalc(player, targetPos);
        helper.assertTrue(resSuccess == InteractionResult.SUCCESS,
                "Staking in Settled land must succeed");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "Blessed talc must be consumed on successful stake");
        helper.assertTrue(record.equals(data.getEmpireByChunk(ChunkKeys.pack(targetChunk.x, targetChunk.z))),
                "Chunk index must reflect new stake");

        // Cleanup
        villager.discard();
        helper.getLevel().setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3);
        frontier.clearHabitation(targetPos, 8);
        data.disbandEmpire(record.id(), server);
        helper.succeed();
    }

    private static InteractionResult useBlessedTalc(Player player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND, hit);
        return player.getItemInHand(InteractionHand.MAIN_HAND).useOn(context);
    }
}
