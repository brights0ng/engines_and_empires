// Written by Claude.
package dev.brights0ng.empire.gametest;

import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.SPOT;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.foundWithBeef;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.interact;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.litBonfire;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.offeringFor;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.placeLitBonfire;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.withTierChecksPassing;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
 * Members: founding makes the founder the one member, joining at the main bonfire with the god's meat, who may not join,
 * what a member who did not found the empire may do, and saving members. Every test runs to the end in its first tick,
 * so the empires it makes never meet another test's.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class MembershipGameTests {

    @GameTest(template = GameTestStructures.EMPTY)
    public static void foundingMakesThePlayerTheSoleMember(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            try {
                helper.assertTrue(record.members().equals(Set.of(founder.getUUID())), "the founder is the one member: "
                        + record.members());
                helper.assertTrue(record == data.getEmpireOf(founder.getUUID()), "the founder's empire is this one");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void anOutsiderJoinsWithFiveFreshGodMeatAtTheMainBonfire(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            try {
                Player outsider = helper.makeMockPlayer(GameType.SURVIVAL);
                outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PORKCHOP, 5));
                interact(helper.getLevel(), helper.absolutePos(SPOT), outsider);
                helper.assertTrue(!record.isMember(outsider.getUUID()) && outsider.getMainHandItem().getCount() == 5,
                        "another god's meat does not join the Iron Aurochs' empire");

                outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 4));
                interact(helper.getLevel(), helper.absolutePos(SPOT), outsider);
                helper.assertTrue(!record.isMember(outsider.getUUID()) && outsider.getMainHandItem().getCount() == 4,
                        "4 beef are not enough to join and are not taken");

                outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 7));
                interact(helper.getLevel(), helper.absolutePos(SPOT), outsider);
                helper.assertTrue(record.members().size() == 2 && record.isMember(outsider.getUUID()),
                        "5 beef join the outsider: " + record.members());
                helper.assertTrue(outsider.getMainHandItem().getCount() == 2, "exactly 5 beef taken, hand holds "
                        + outsider.getMainHandItem());
                helper.assertTrue(record == data.getEmpireOf(outsider.getUUID()), "the joiner's empire is this one");
                helper.assertTrue(record.state().dominion() == 10 && record.sacrificeCount() == 0,
                        "joining pays no demand and adds no dominion");
                helper.assertTrue(List.copyOf(record.members()).get(0).equals(founder.getUUID()),
                        "members keep the order they joined in");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void joiningIsRefusedAwayFromTheMainBonfireAndToAMemberOfAnotherEmpire(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            EmpireSavedData data = EmpireSavedData.of(server);
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            BlockPos mainPos = helper.absolutePos(SPOT);
            BlockPos secondaryPos = mainPos.offset(5, 0, 0);
            Player outsider = helper.makeMockPlayer(GameType.SURVIVAL);
            Player foreigner = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord foreign = data.createEmpire(foreigner.getUUID(), ChunkKeys.pack(-310, 310), null,
                    helper.getLevel().getGameTime());
            try {
                helper.getLevel().setBlock(secondaryPos, litBonfire(), 3);
                data.onBonfirePlaced(secondaryPos, server);
                helper.assertTrue(mainPos.equals(record.mainBonfire()), "the founding bonfire stays the main one");

                outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                interact(helper.getLevel(), secondaryPos, outsider);
                helper.assertTrue(!record.isMember(outsider.getUUID()) && outsider.getMainHandItem().getCount() == 5,
                        "a bonfire that is not the main one takes no one in, and no beef");

                foreigner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                interact(helper.getLevel(), mainPos, foreigner);
                helper.assertTrue(!record.isMember(foreigner.getUUID()) && foreigner.getMainHandItem().getCount() == 5,
                        "a member of another empire cannot join, and keeps the beef");
                helper.assertTrue(foreign == data.getEmpireOf(foreigner.getUUID()), "the foreigner stays in their empire");
                helper.assertTrue(record.members().size() == 1, "nobody joined: " + record.members());
            } finally {
                data.onBonfireRemoved(secondaryPos, server);
                helper.getLevel().setBlock(secondaryPos, Blocks.AIR.defaultBlockState(), 3);
                data.disbandEmpire(record.id(), null);
                data.disbandEmpire(foreign.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void aGodlessEmpireTakesNobodyIn(GameTestHelper helper) {
        placeLitBonfire(helper, SPOT);
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
        BlockPos pos = helper.absolutePos(SPOT);
        ChunkPos chunk = new ChunkPos(pos);
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord record = data.createEmpire(founder.getUUID(), ChunkKeys.pack(chunk.x, chunk.z), pos,
                helper.getLevel().getGameTime());
        try {
            Player outsider = helper.makeMockPlayer(GameType.SURVIVAL);
            outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
            interact(helper.getLevel(), pos, outsider);
            helper.assertTrue(!record.isMember(outsider.getUUID()) && outsider.getMainHandItem().getCount() == 5,
                    "a godless empire takes nobody in, and no beef");
            helper.assertTrue(record.god() == null, "an outsider's meat does not give the empire a god");
        } finally {
            data.disbandEmpire(record.id(), null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void aMemberWhoDidNotFoundTheEmpirePaysBlessesAndStakes(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            BlockPos mainPos = helper.absolutePos(SPOT);
            try {
                Player member = joinWithBeef(helper, record);

                DemandLine tier2 = record.currentDemand().get(1);
                member.setItemInHand(InteractionHand.MAIN_HAND, offeringFor(tier2, 1));
                interact(helper.getLevel(), mainPos, member);
                helper.assertTrue(member.getMainHandItem().isEmpty() && record.currentDemand().get(1).paid() == 1,
                        "the member pays towards a demand line: " + record.currentDemand());

                member.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.RAW_TALC.get(), 2));
                interact(helper.getLevel(), mainPos, member);
                helper.assertTrue(member.getMainHandItem().getCount() == 1, "the member blesses one raw talc");
                helper.assertTrue(member.getInventory().contains(new ItemStack(EmpireContent.BLESSED_TALC.get())),
                        "the member gets the blessed talc");

                ChunkPos center = new ChunkPos(mainPos);
                BlockPos next = new BlockPos((center.x + 2) << 4, mainPos.getY(), center.z << 4);
                member.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.BLESSED_TALC.get(), 3));
                helper.assertTrue(useOn(member, next) == InteractionResult.SUCCESS, "the member stakes a chunk");
                helper.assertTrue(record.state().staked().contains(ChunkKeys.pack(center.x + 2, center.z)),
                        "the member's stake is the empire's");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void membersSurviveSaveAndLoadAndALegacyFounderLoadsAsTheOneMember(GameTestHelper helper) {
        EmpireSavedData data = new EmpireSavedData();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        EmpireRecord record = data.createEmpire(first, ChunkKeys.pack(40, -40), null, 0L);
        helper.assertTrue(data.addMember(record, second), "a player in no empire is added");
        helper.assertTrue(!data.addMember(record, second), "a member is not added twice");
        EmpireRecord other = data.createEmpire(UUID.randomUUID(), ChunkKeys.pack(80, -80), null, 0L);
        helper.assertTrue(!data.addMember(other, first), "a player in one empire cannot be added to another");

        CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(saved.getInt("version") == 2, "the empires save as version 2");
        EmpireSavedData loaded = EmpireSavedData.load(saved, helper.getLevel().registryAccess());
        EmpireRecord back = loaded.getEmpire(record.id());
        helper.assertTrue(back != null && List.copyOf(back.members()).equals(List.of(first, second)),
                "members load back in order: " + (back == null ? null : back.members()));
        helper.assertTrue(loaded.getEmpireOf(second) == back, "the member index is rebuilt on load");

        CompoundTag legacy = record.save();
        legacy.remove("members");
        legacy.putUUID("founder", first);
        EmpireRecord old = EmpireRecord.load(legacy);
        helper.assertTrue(old.members().equals(Set.of(first)), "a legacy record's founder is its one member: "
                + old.members());
        helper.succeed();
    }

    // ---- helpers ----

    /** A new player who joins the empire with 5 beef at the test's main bonfire. */
    static Player joinWithBeef(GameTestHelper helper, EmpireRecord record) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
        interact(helper.getLevel(), helper.absolutePos(SPOT), player);
        helper.assertTrue(record.isMember(player.getUUID()) && player.getMainHandItem().isEmpty(),
                "5 beef join the player to the empire");
        return player;
    }

    static InteractionResult useOn(Player player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }
}
