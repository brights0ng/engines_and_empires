// Written by Claude; empires given a main bonfire (protection needs one) by Claude.
package dev.brights0ng.empire.gametest;

import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.clearArea;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.foundWithBeef;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.withTierChecksPassing;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireItem;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Divine protection and bonfire break rights (Bright's design). Players act through their game mode, so the real
 * events fire. Every test finishes in its first tick and disbands its empires, so no other test meets them.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class ProtectionGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = GameTestStructures.EMPTY)
    public static void anOutsiderCannotBreakPlaceOrUseALeverInTheDivineAreaButAMemberCan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level.getServer());
        ServerPlayer member = player(helper, "empire_member");
        ServerPlayer outsider = player(helper, "empire_outsider");
        EmpireRecord record = withMain(data,
                data.createEmpire(member.getUUID(), chunkOf(helper.absolutePos(SPOT)), null, level.getGameTime()));
        BlockPos stone = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos floor = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos lever = floor.above();
        try {
            helper.assertTrue(data.getDivineOwner(chunkOf(stone)) == record && data.getDivineOwner(chunkOf(lever)) == record,
                    "the test spot is in the empire's divine area");
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 3);

            // The outsider: no breaking, no placing, no lever.
            helper.assertTrue(outsider.getDigSpeed(level.getBlockState(stone), stone) == 0.0F, "an outsider digs at speed 0");
            helper.assertFalse(outsider.gameMode.destroyBlock(stone), "an outsider's break is refused");
            helper.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "the stone is still there");

            outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 4));
            use(outsider, stone);
            helper.assertTrue(level.getBlockState(stone.above()).isAir(), "an outsider places nothing");
            BlockEvent.EntityPlaceEvent place = new BlockEvent.EntityPlaceEvent(
                    BlockSnapshot.create(level.dimension(), level, stone.above()), level.getBlockState(stone), outsider);
            helper.assertTrue(NeoForge.EVENT_BUS.post(place).isCanceled(), "an outsider's placement event is cancelled");

            outsider.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            use(outsider, lever);
            helper.assertFalse(level.getBlockState(lever).getValue(LeverBlock.POWERED), "an outsider cannot pull the lever");

            // A member: all three.
            use(member, lever);
            helper.assertTrue(level.getBlockState(lever).getValue(LeverBlock.POWERED), "a member pulls the lever");
            member.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 4));
            use(member, stone);
            helper.assertTrue(level.getBlockState(stone.above()).is(Blocks.COBBLESTONE), "a member places a block");
            member.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(member.getDigSpeed(level.getBlockState(stone), stone) > 0.0F, "a member digs");
            helper.assertTrue(member.gameMode.destroyBlock(stone), "a member's break goes through");
            helper.assertTrue(level.getBlockState(stone).isAir(), "the stone is gone");
        } finally {
            data.disbandEmpire(record.id(), null);
            for (BlockPos pos : List.of(stone, stone.above(), lever, floor)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            helper.killAllEntitiesOfClass(ItemEntity.class);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void anOutsiderBreaksBlocksInAStakedChunkOutsideTheDivineArea(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level.getServer());
        ServerPlayer outsider = player(helper, "empire_outsider");
        BlockPos stone = helper.absolutePos(SPOT);
        EmpireRecord record = empireStakingButNotDivine(helper, data, UUID.randomUUID(), stone);
        try {
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            helper.assertTrue(outsider.getDigSpeed(level.getBlockState(stone), stone) > 0.0F, "an outsider digs");
            helper.assertTrue(outsider.gameMode.destroyBlock(stone), "an outsider's break goes through");
            helper.assertTrue(level.getBlockState(stone).isAir(), "the stone is gone");
        } finally {
            data.disbandEmpire(record.id(), null);
            level.setBlock(stone, Blocks.AIR.defaultBlockState(), 3);
            helper.killAllEntitiesOfClass(ItemEntity.class);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void aTntExplosionSparesTheDivineAreaButNotTheChunkBeyondIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level.getServer());
        BlockPos center = helper.absolutePos(new BlockPos(3, 4, 3));
        long chunk = chunkOf(center);
        List<BlockPos> dirt = List.of(center.above(), center.below(), center.north(), center.south(), center.east(), center.west());

        // Inside: the empire's core is this chunk.
        EmpireRecord inside = withMain(data, data.createEmpire(UUID.randomUUID(), chunk, null, level.getGameTime()));
        try {
            dirt.forEach(pos -> level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3));
            explode(level, center);
            for (BlockPos pos : dirt) {
                helper.assertTrue(level.getBlockState(pos).is(Blocks.DIRT), "the divine area keeps its blocks: " + pos);
            }
        } finally {
            data.disbandEmpire(inside.id(), null);
        }

        // One chunk outside: the core is two chunks away, on the side the dirt does not reach towards.
        EmpireRecord beyond = data.createEmpire(UUID.randomUUID(), awayCore(center, 2), null, level.getGameTime());
        try {
            for (BlockPos pos : dirt) {
                helper.assertTrue(data.getDivineOwner(chunkOf(pos)) == null, "the dirt is outside the divine area: " + pos);
            }
            explode(level, center);
            for (BlockPos pos : dirt) {
                helper.assertTrue(level.getBlockState(pos).isAir(), "the explosion breaks blocks outside it: " + pos);
            }
        } finally {
            data.disbandEmpire(beyond.id(), null);
            dirt.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
            helper.killAllEntitiesOfClass(ItemEntity.class);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void anOutsiderStillJoinsThroughTheMainBonfireWithGodMeat(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            ServerLevel level = helper.getLevel();
            EmpireSavedData data = EmpireSavedData.of(level.getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            BlockPos main = helper.absolutePos(SPOT);
            try {
                helper.assertTrue(data.getDivineOwner(chunkOf(main)) == record, "the main bonfire is in the divine area");
                ServerPlayer outsider = player(helper, "empire_joiner");

                outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
                use(outsider, main);
                helper.assertTrue(level.getBlockState(main).getValue(BonfireBlock.LIT), "an outsider cannot put the fire out");

                outsider.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                helper.assertTrue(use(outsider, main.above()).consumesAction(), "an outsider may read the demand at a part");

                outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                use(outsider, main);
                helper.assertTrue(record.isMember(outsider.getUUID()), "the outsider joined: " + record.members());
            } finally {
                data.disbandEmpire(record.id(), null);
                clearArea(helper, SPOT);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void onlyMembersBreakAClaimedBonfireOutsideTheDivineAreaAndAnyoneAnUnclaimedOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level.getServer());
        ServerPlayer member = player(helper, "empire_member");
        ServerPlayer outsider = player(helper, "empire_outsider");
        BlockPos main = helper.absolutePos(SPOT);
        EmpireRecord record = empireStakingButNotDivine(helper, data, member.getUUID(), main);
        try {
            clearArea(helper, SPOT);
            level.setBlock(main, EmpireContent.BONFIRE.get().defaultBlockState(), 3);
            BonfireItem.placeParts(level, main);
            BlockPos part = main.above();
            helper.assertTrue(level.getBlockState(part).is(EmpireContent.BONFIRE_PART.get()), "the bonfire has its parts");

            outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            member.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            helper.assertTrue(outsider.getDigSpeed(level.getBlockState(main), main) == 0.0F, "an outsider digs the bonfire at 0");
            helper.assertTrue(outsider.getDigSpeed(level.getBlockState(part), part) == 0.0F, "and its parts at 0");
            helper.assertTrue(member.getDigSpeed(level.getBlockState(main), main) > 0.0F, "a member digs it with an iron axe");
            helper.assertFalse(outsider.gameMode.destroyBlock(part), "an outsider cannot break a part");
            helper.assertFalse(outsider.gameMode.destroyBlock(main), "an outsider cannot break the bonfire");
            helper.assertTrue(level.getBlockState(main).is(EmpireContent.BONFIRE.get()), "the bonfire stands");

            data.disbandEmpire(record.id(), null);
            helper.assertTrue(outsider.getDigSpeed(level.getBlockState(main), main) > 0.0F, "unclaimed, anyone digs it");
            helper.assertTrue(outsider.gameMode.destroyBlock(main), "unclaimed, anyone breaks it");
            helper.assertFalse(level.getBlockState(main).is(EmpireContent.BONFIRE.get()), "the bonfire is gone");
        } finally {
            data.disbandEmpire(record.id(), null);
            clearArea(helper, SPOT);
            helper.killAllEntitiesOfClass(ItemEntity.class);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void theDivineAreaFollowsAReselectedMainBonfire(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        BlockPos posMain = new BlockPos(140 * 16 + 8, 64, -140 * 16 + 8);
        BlockPos posNew = new BlockPos(143 * 16 + 8, 64, -140 * 16 + 8);
        EmpireRecord record = data.createEmpire(UUID.randomUUID(), ChunkKeys.pack(140, -140), posMain,
                server.overworld().getGameTime());
        try {
            // Stake the 3x3 around chunk (143, -140), so a bonfire there can become main.
            record.state().addDominion(20);
            long day = DawnClock.dayOf(server.overworld().getDayTime());
            for (int x = 142; x <= 144; x++) {
                for (int z = -141; z <= -139; z++) {
                    StakeResult result = data.stakeChunk(record, ChunkKeys.pack(x, z), day, true);
                    helper.assertTrue(result == StakeResult.OK, "staked " + x + "," + z + ": " + result);
                }
            }
            data.onBonfirePlaced(posNew, server);
            helper.assertTrue(posMain.equals(record.mainBonfire()), "the first bonfire stays main");
            helper.assertTrue(data.getDivineOwner(ChunkKeys.pack(141, -139)) == record, "the old area is divine");
            helper.assertTrue(data.getDivineOwner(ChunkKeys.pack(143, -140)) == null, "the new bonfire's chunk is not yet");

            data.onBonfireRemoved(posMain, server);
            helper.assertTrue(posNew.equals(record.mainBonfire()), "the new bonfire is main");
            helper.assertTrue(data.getDivineOwner(ChunkKeys.pack(143, -140)) == record
                    && data.getDivineOwner(ChunkKeys.pack(144, -141)) == record
                    && data.getDivineOwner(ChunkKeys.pack(142, -139)) == record, "the divine area moved to it");
            helper.assertTrue(data.getDivineOwner(ChunkKeys.pack(140, -140)) == null
                    && data.getDivineOwner(ChunkKeys.pack(141, -140)) == null, "the old area is no longer divine");
        } finally {
            data.disbandEmpire(record.id(), server);
            data.onBonfireRemoved(posNew, server);
        }
        helper.assertTrue(data.getDivineOwner(ChunkKeys.pack(143, -140)) == null, "a disbanded empire has no divine area");
        helper.succeed();
    }

    // ---- helpers ----

    /**
     * A survival player whose clicks and breaks go through its game mode and fire the real events. It borrows a fake
     * player's harmless connection but does not report itself as fake: fake players (Create's deployers) always pass.
     */
    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name)) {
            @Override
            public boolean isFakePlayer() {
                return false;
            }
        };
        player.moveTo(Vec3.atCenterOf(helper.absolutePos(SPOT)));
        return player;
    }

    /** A right click on the top of {@code pos} with the main hand, as the server handles one. */
    private static InteractionResult use(ServerPlayer player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }

    private static void explode(ServerLevel level, BlockPos center) {
        Vec3 at = Vec3.atCenterOf(center);
        level.explode(null, at.x, at.y, at.z, 3.0F, Level.ExplosionInteraction.TNT);
    }

    /**
     * An empire whose core is two chunks east or west of {@code pos}'s chunk (away from the nearer chunk edge) and which
     * has also staked that chunk: claimed, but outside the divine area, with a block either side of {@code pos} too.
     */
    private static EmpireRecord empireStakingButNotDivine(GameTestHelper helper, EmpireSavedData data, UUID member, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        EmpireRecord record = data.createEmpire(member, awayCore(pos, 2), null, level.getGameTime());
        record.state().addDominion(5);
        StakeResult result = data.stakeChunk(record, chunkOf(pos), DawnClock.dayOf(level.getDayTime()), true);
        helper.assertTrue(result == StakeResult.OK, "the test chunk is staked: " + result);
        helper.assertTrue(data.getEmpireByChunk(chunkOf(pos)) == record && data.getDivineOwner(chunkOf(pos)) == null,
                "the test chunk is claimed but not divine");
        return record;
    }

    /** The chunk {@code distance} chunks along x from {@code pos}'s, on the side away from {@code pos}'s nearer x edge. */
    private static long awayCore(BlockPos pos, int distance) {
        int cx = pos.getX() >> 4;
        return ChunkKeys.pack((pos.getX() & 15) >= 8 ? cx - distance : cx + distance, pos.getZ() >> 4);
    }

    private static long chunkOf(BlockPos pos) {
        return ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Gives the empire a main bonfire in its founding chunk (a record only: no block, nothing in the bonfire registry),
     * since only an empire with a main bonfire has a divine area.
     */
    private static EmpireRecord withMain(EmpireSavedData data, EmpireRecord record) {
        long core = record.state().foundingChunk();
        record.setMainBonfire(new BlockPos((ChunkKeys.getX(core) << 4) + 8, 0, (ChunkKeys.getZ(core) << 4) + 8));
        data.rebuildDivineIndex();
        return record;
    }

    private ProtectionGameTests() {
    }
}
