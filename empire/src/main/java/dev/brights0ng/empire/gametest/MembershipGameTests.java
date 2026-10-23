// Written by Claude.
package dev.brights0ng.empire.gametest;

import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.IRON_AUROCHS;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.foundWithBeef;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.interact;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.litBonfire;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.offeringFor;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.withTierChecksPassing;

import java.util.List;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfirePartBlock;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.membership.LeaveInteractions;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Empire members (Bright's design): every member has equal control; a player in no empire joins with 5 of its god's
 * founding meat at its main bonfire; a member leaves by offering 5 tarnished talc twice, with a wait between. Tests that
 * finish in their first tick use the test's own spot; the leave tests span ticks, so their empires sit far from every
 * test structure.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class MembershipGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);
    /** Where the leave timing test's empire is: no block is needed there. */
    private static final BlockPos LEAVE_FAR = new BlockPos(-1632, 64, 1632);
    /** Where the last-member test's empire and its main bonfire are. */
    private static final BlockPos FROZEN_FAR = new BlockPos(1632, 64, 1632);

    @GameTest(template = GameTestStructures.EMPTY)
    public static void foundingMakesTheFounderTheSoleMember(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            try {
                helper.assertTrue(record.members().equals(java.util.Set.of(founder.getUUID())),
                        "the founder is the only member: " + record.members());
                helper.assertTrue(data.getEmpireOf(founder.getUUID()) == record, "the founder's empire is found by member");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void anOutsiderJoinsWithFiveFreshGodMeatAtTheLitMainBonfire(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            try {
                Player outsider = helper.makeMockPlayer(GameType.SURVIVAL);
                outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 7));
                interact(helper.getLevel(), helper.absolutePos(SPOT), outsider);
                helper.assertTrue(record.members().size() == 2 && record.isMember(outsider.getUUID()),
                        "the outsider joined: " + record.members());
                helper.assertTrue(outsider.getMainHandItem().getCount() == 2, "exactly 5 beef were taken");
                helper.assertTrue(data.getEmpireOf(outsider.getUUID()) == record, "the new member's empire is found");
                helper.assertTrue(record.state().dominion() == 10 && record.sacrificeCount() == 0,
                        "joining pays nothing towards the demand");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void joiningIsRefusedAtANonMainBonfireOrFromAnotherEmpiresMember(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            EmpireSavedData data = EmpireSavedData.of(server);
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            BlockPos mainPos = helper.absolutePos(SPOT);
            BlockPos secondaryPos = mainPos.offset(5, 0, 0);
            Player otherMember = helper.makeMockPlayer(GameType.SURVIVAL);
            // A second empire far away, with no bonfire: its member may not join this one.
            EmpireRecord other = data.createEmpire(otherMember.getUUID(), ChunkKeys.pack(-150, -150), null,
                    helper.getLevel().getGameTime());
            try {
                helper.getLevel().setBlock(secondaryPos, litBonfire(), 3);
                data.onBonfirePlaced(secondaryPos, server);
                helper.assertTrue(mainPos.equals(record.mainBonfire()), "the founding bonfire stays main");

                Player outsider = helper.makeMockPlayer(GameType.SURVIVAL);
                outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                interact(helper.getLevel(), secondaryPos, outsider);
                helper.assertTrue(outsider.getMainHandItem().getCount() == 5 && !record.isMember(outsider.getUUID()),
                        "nothing is taken and no one joins at a bonfire that is not the main one");

                otherMember.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                interact(helper.getLevel(), mainPos, otherMember);
                helper.assertTrue(otherMember.getMainHandItem().getCount() == 5 && !record.isMember(otherMember.getUUID()),
                        "a member of another empire cannot join");
                helper.assertTrue(data.getEmpireOf(otherMember.getUUID()) == other, "they stay in their own empire");
                helper.assertTrue(record.members().size() == 1, "still only the founder: " + record.members());
            } finally {
                data.onBonfireRemoved(secondaryPos, server);
                helper.getLevel().setBlock(secondaryPos, Blocks.AIR.defaultBlockState(), 3);
                data.disbandEmpire(record.id(), null);
                data.disbandEmpire(other.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void aMemberWhoDidNotFoundCanPayBlessAndStake(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            BlockPos mainPos = helper.absolutePos(SPOT);
            try {
                Player member = helper.makeMockPlayer(GameType.SURVIVAL);
                member.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                interact(helper.getLevel(), mainPos, member);
                helper.assertTrue(record.isMember(member.getUUID()), "the second player joined");

                DemandLine tier2 = record.currentDemand().get(1);
                member.setItemInHand(InteractionHand.MAIN_HAND, offeringFor(tier2, 3));
                interact(helper.getLevel(), mainPos, member);
                helper.assertTrue(member.getMainHandItem().isEmpty() && record.currentDemand().get(1).paid() == 3,
                        "the member pays a demand line: " + record.currentDemand());

                member.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.RAW_TALC.get(), 1));
                interact(helper.getLevel(), mainPos, member);
                helper.assertTrue(member.getInventory().contains(new ItemStack(EmpireContent.BLESSED_TALC.get())),
                        "the member blesses talc");

                ChunkPos center = new ChunkPos(mainPos);
                BlockPos stakePos = new BlockPos((center.x + 2) << 4, mainPos.getY(), center.z << 4);
                member.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.BLESSED_TALC.get(), 1));
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(stakePos), Direction.UP, stakePos, false);
                member.getMainHandItem().useOn(new UseOnContext(member, InteractionHand.MAIN_HAND, hit));
                helper.assertTrue(record.state().staked().contains(ChunkKeys.pack(center.x + 2, center.z)),
                        "the member stakes a chunk");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void tarnishedTalcAtABonfirePartWarnsWithoutTakingAnything(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            try {
                // Unlit: leaving does not need the fire.
                helper.setBlock(SPOT, helper.getBlockState(SPOT).setValue(BonfireBlock.LIT, false));
                BlockPos partPos = helper.absolutePos(SPOT.offset(BonfirePartBlock.PART_OFFSETS[0]));
                founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.TARNISHED_TALC.get(), 5));
                interact(helper.getLevel(), partPos, founder);
                helper.assertTrue(founder.getMainHandItem().getCount() == 5 && record.isMember(founder.getUUID()),
                        "the first offer only warns");
                helper.assertTrue(record.state().stakedCount() == 9, "the talc did not unstake the bonfire's chunk");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, timeoutTicks = 400)
    public static void leavingWarnsThenWaitsThenTakesFiveTalcAndAWarningLapses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level.getServer());
        Player leaver = helper.makeMockPlayer(GameType.SURVIVAL);
        Player lingerer = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord record = data.createEmpire(leaver.getUUID(), chunkOf(LEAVE_FAR), null, level.getGameTime());
        data.addMember(record, lingerer.getUUID());
        leaver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.TARNISHED_TALC.get(), 6));
        lingerer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.TARNISHED_TALC.get(), 5));

        LeaveInteractions.tryLeave(leaver.getMainHandItem(), level, LEAVE_FAR, leaver);
        LeaveInteractions.tryLeave(lingerer.getMainHandItem(), level, LEAVE_FAR, lingerer);
        helper.assertTrue(leaver.getMainHandItem().getCount() == 6 && lingerer.getMainHandItem().getCount() == 5
                && record.members().size() == 2, "the warning takes nothing");

        helper.runAtTickTime(40, () -> {
            LeaveInteractions.tryLeave(leaver.getMainHandItem(), level, LEAVE_FAR, leaver);
            helper.assertTrue(leaver.getMainHandItem().getCount() == 6 && record.isMember(leaver.getUUID()),
                    "an offer 40 ticks after the warning is ignored");
        });
        helper.runAtTickTime(80, () -> {
            LeaveInteractions.tryLeave(leaver.getMainHandItem(), level, LEAVE_FAR, leaver);
            helper.assertTrue(leaver.getMainHandItem().getCount() == 1, "an offer 80 ticks after the warning takes 5");
            helper.assertTrue(!record.isMember(leaver.getUUID()) && data.getEmpireOf(leaver.getUUID()) == null,
                    "and the player has left");
            helper.assertTrue(record.members().equals(java.util.Set.of(lingerer.getUUID())), "one member is left");
        });
        helper.runAtTickTime(300, () -> {
            LeaveInteractions.tryLeave(lingerer.getMainHandItem(), level, LEAVE_FAR, lingerer);
            helper.assertTrue(lingerer.getMainHandItem().getCount() == 5 && record.isMember(lingerer.getUUID()),
                    "an offer 300 ticks after the warning only warns again");
            data.disbandEmpire(record.id(), null);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, timeoutTicks = 200)
    public static void theLastMemberLeavingFreezesTheEmpireForAnyoneToJoin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level.getServer());
        ChunkPos frozenChunk = new ChunkPos(FROZEN_FAR);
        level.setChunkForced(frozenChunk.x, frozenChunk.z, true);
        level.setBlock(FROZEN_FAR, litBonfire(), 3);
        Player last = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord record = data.createEmpire(last.getUUID(), chunkOf(FROZEN_FAR), FROZEN_FAR, level.getGameTime());
        record.setGod(IRON_AUROCHS);
        int dominion = record.state().dominion();
        last.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.TARNISHED_TALC.get(), 5));
        interact(level, FROZEN_FAR, last);

        helper.runAtTickTime(70, () -> {
            try {
                interact(level, FROZEN_FAR, last);
                helper.assertTrue(last.getMainHandItem().isEmpty() && record.members().isEmpty(),
                        "the last member left: " + record.members());
                helper.assertTrue(data.getEmpire(record.id()) == record, "the empire is not disbanded");
                helper.assertTrue(record.state().stakedCount() == 9 && data.isChunkStaked(chunkOf(FROZEN_FAR)),
                        "its chunks stay staked");
                helper.assertTrue(record.state().dominion() == dominion, "its dominion is unchanged");
                helper.assertTrue(FROZEN_FAR.equals(record.mainBonfire()) && IRON_AUROCHS.equals(record.god()),
                        "it keeps its main bonfire and god");

                Player newcomer = helper.makeMockPlayer(GameType.SURVIVAL);
                newcomer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                interact(level, FROZEN_FAR, newcomer);
                helper.assertTrue(record.members().equals(java.util.Set.of(newcomer.getUUID()))
                        && newcomer.getMainHandItem().isEmpty(), "a new player joins the frozen empire");
            } finally {
                data.disbandEmpire(record.id(), null);
                level.setBlock(FROZEN_FAR, Blocks.AIR.defaultBlockState(), 3);
                level.setChunkForced(frozenChunk.x, frozenChunk.z, false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void membersSurviveSaveAndLoadAndALegacyFounderLoadsAsTheOnlyMember(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = new EmpireSavedData();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        EmpireRecord record = data.createEmpire(first, ChunkKeys.pack(3, 4), null, 0L);
        data.addMember(record, second);
        data.addMember(record, third);
        data.removeMember(record, second);

        CompoundTag saved = data.save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(saved.getInt("version") == 2, "the save format is version 2");
        EmpireSavedData loaded = EmpireSavedData.load(saved, server.registryAccess());
        EmpireRecord loadedRecord = loaded.getEmpire(record.id());
        helper.assertTrue(loadedRecord != null && List.copyOf(loadedRecord.members()).equals(List.of(first, third)),
                "members load in joining order: " + (loadedRecord != null ? loadedRecord.members() : null));
        helper.assertTrue(loaded.getEmpireOf(third) == loadedRecord && loaded.getEmpireOf(second) == null,
                "the member index is rebuilt");

        // A version 1 record: a founder and no members.
        CompoundTag legacyRecord = record.save();
        legacyRecord.remove("members");
        legacyRecord.putUUID("founder", first);
        ListTag empires = new ListTag();
        empires.add(legacyRecord);
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("version", 1);
        legacy.put("empires", empires);
        EmpireSavedData legacyLoaded = EmpireSavedData.load(legacy, server.registryAccess());
        EmpireRecord legacyEmpire = legacyLoaded.getEmpireOf(first);
        helper.assertTrue(legacyEmpire != null && legacyEmpire.members().equals(java.util.Set.of(first)),
                "a legacy founder loads as the only member");
        helper.succeed();
    }

    private static long chunkOf(BlockPos pos) {
        return ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private MembershipGameTests() {
    }
}
