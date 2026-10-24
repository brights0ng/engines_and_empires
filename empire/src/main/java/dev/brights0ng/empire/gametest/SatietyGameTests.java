// Written by Claude.
package dev.brights0ng.empire.gametest;

import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.IRON_AUROCHS;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.foundWithBeef;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.interact;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.litBonfire;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.payInFull;
import static dev.brights0ng.empire.gametest.GodSacrificeGameTests.withTierChecksPassing;

import java.util.List;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.god.GodDefinition;
import dev.brights0ng.empire.god.GodHunger;
import dev.brights0ng.empire.god.Gods;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.empire.rules.GodSatiety;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A god's hunger: it grows only while a member is online, feeding resets it (founding, joining, a 5-meat offering,
 * paying a demand), a hungry god puts out the main bonfire and a starved one abandons the empire. Mock players are never
 * online, so the tests drive {@link GodHunger#step} themselves. The tests at the test spot run to the end in their first
 * tick; the longer ones use empires far from every test structure.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class SatietyGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);
    /** Far from every test structure and from the other tests' far empires. */
    private static final BlockPos OFFLINE_FAR = new BlockPos(2400, 64, -2400);
    private static final BlockPos HUNGRY_FAR = new BlockPos(-1632, 64, -1632);

    @GameTest(template = GameTestStructures.EMPTY, timeoutTicks = 200)
    public static void hungerDoesNotAdvanceWithNoMemberOnline(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        Player member = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord record = data.createEmpire(member.getUUID(), chunkOf(OFFLINE_FAR), null, level.getGameTime());
        record.setGod(IRON_AUROCHS);
        record.setHungerTicks(100);
        long limit = GodHunger.limitTicks(level);

        GodHunger.step(server, data, record, false, limit);
        helper.assertTrue(record.hungerTicks() == 100, "no member online: hunger stays 100, is " + record.hungerTicks());
        GodHunger.step(server, data, record, true, limit);
        helper.assertTrue(record.hungerTicks() == 100 + GodHunger.STEP_TICKS, "a member online: hunger grows by a step");

        helper.runAtTickTime(60, () -> {
            try {
                helper.assertTrue(record.hungerTicks() == 120,
                        "the server's own ticks leave it alone (the mock member is not online), is " + record.hungerTicks());
                data.removeMember(record, member.getUUID());
                GodHunger.step(server, data, record, true, limit);
                helper.assertTrue(record.hungerTicks() == 120, "a frozen empire never grows hungry");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void foundingAndJoiningFeedTheGod(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            try {
                helper.assertTrue(record.hungerTicks() == 0, "a new empire's god is fed");
                record.setHungerTicks(5000);
                Player joiner = helper.makeMockPlayer(GameType.SURVIVAL);
                joiner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                interact(helper.getLevel(), helper.absolutePos(SPOT), joiner);
                helper.assertTrue(record.isMember(joiner.getUUID()), "5 beef join the empire");
                helper.assertTrue(record.hungerTicks() == 0, "joining feeds the god, hunger is " + record.hungerTicks());
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void fiveMeatFeedTheGodAndAreRefusedWhenItIsSated(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, player);
            BlockPos bonfire = helper.absolutePos(SPOT);
            try {
                int line = foundingMeatLine(record);
                helper.assertTrue(line >= 0, "the first demand asks for the founding meat: " + record.currentDemand());
                DemandLine meat = record.currentDemand().get(line);
                if (meat.remaining() >= 5) {
                    // 5 or more paid towards the founding meat line in one click feed the god too.
                    record.setHungerTicks(5000);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, meat.remaining()));
                    interact(helper.getLevel(), bonfire, player);
                    helper.assertTrue(record.currentDemand().get(line).isPaid(), "the founding meat line is paid");
                    helper.assertTrue(record.hungerTicks() == 0, "paying 5+ of it at once feeds the god");
                } else {
                    record.setDemandLine(line, meat.withPaid(meat.required()));
                }

                // No founding meat line unpaid: 5 beef are a plain offering, refused while the god is sated.
                record.setHungerTicks(0);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 7));
                interact(helper.getLevel(), bonfire, player);
                helper.assertTrue(player.getMainHandItem().getCount() == 7, "a sated god takes nothing");

                record.setHungerTicks(5000);
                int sacrifices = record.sacrificeCount();
                interact(helper.getLevel(), bonfire, player);
                helper.assertTrue(player.getMainHandItem().getCount() == 2, "exactly 5 beef are taken, left "
                        + player.getMainHandItem().getCount());
                helper.assertTrue(record.hungerTicks() == 0, "the offering feeds the god");
                helper.assertTrue(record.sacrificeCount() == sacrifices, "and pays no demand");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void completingADemandFeedsTheGod(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, player);
            try {
                // The founding meat line is marked paid, so only the demand's completion can feed the god here.
                int line = foundingMeatLine(record);
                DemandLine meat = record.currentDemand().get(line);
                record.setDemandLine(line, meat.withPaid(meat.required()));
                record.setHungerTicks(5000);
                payInFull(helper, helper.getLevel(), helper.absolutePos(SPOT), player, record);
                helper.assertTrue(record.hungerTicks() == 0, "a paid demand feeds the god, hunger is " + record.hungerTicks());
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, timeoutTicks = 400)
    public static void aHungryGodPutsOutTheLitMainBonfire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        ChunkPos chunk = new ChunkPos(HUNGRY_FAR);
        level.setChunkForced(chunk.x, chunk.z, true);
        level.setBlock(HUNGRY_FAR, litBonfire().setValue(BonfireBlock.ROARING, true), 3);
        Player member = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord record = data.createEmpire(member.getUUID(), chunkOf(HUNGRY_FAR), HUNGRY_FAR, level.getGameTime());
        record.setGod(IRON_AUROCHS);
        long limit = GodHunger.limitTicks(level);
        long start = GodSatiety.warnTicks(limit);
        record.setHungerTicks(start);
        helper.assertTrue(GodSatiety.state(start, limit) == GodSatiety.State.HUNGRY, "75% of the limit is hungry");

        for (int t = GodHunger.STEP_TICKS; t <= GodSatiety.EXTINGUISH_INTERVAL_TICKS; t += GodHunger.STEP_TICKS) {
            helper.runAtTickTime(t, () -> GodHunger.step(server, data, record, true, limit));
        }
        helper.runAtTickTime(GodSatiety.EXTINGUISH_INTERVAL_TICKS + 1, () -> {
            try {
                BlockState state = level.getBlockState(HUNGRY_FAR);
                helper.assertTrue(state.getBlock() instanceof BonfireBlock, "the bonfire is still there");
                helper.assertTrue(!state.getValue(BonfireBlock.LIT) && !state.getValue(BonfireBlock.ROARING),
                        "the hungry god put the bonfire out within 300 ticks");
                helper.assertTrue(record.hungerTicks() == start + GodSatiety.EXTINGUISH_INTERVAL_TICKS,
                        "hunger grew by 300, is " + record.hungerTicks());
                helper.assertTrue(IRON_AUROCHS.equals(record.god()), "a hungry god has not left yet");
            } finally {
                data.disbandEmpire(record.id(), null);
                level.setBlock(HUNGRY_FAR, Blocks.AIR.defaultBlockState(), 3);
                level.setChunkForced(chunk.x, chunk.z, false);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void aStarvedGodAbandonsTheEmpireWhichKeepsItsLandAndCanAdoptAgain(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            EmpireSavedData data = EmpireSavedData.of(server);
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, player);
            try {
                int dominion = record.state().dominion();
                List<Long> staked = List.copyOf(record.state().staked());
                long limit = GodHunger.limitTicks(helper.getLevel());
                record.setHungerTicks(limit - GodHunger.STEP_TICKS);
                GodHunger.step(server, data, record, true, limit);

                helper.assertTrue(record.god() == null, "the god is gone");
                helper.assertTrue(record.currentDemand().isEmpty(), "with its demand");
                helper.assertTrue(record.hungerTicks() == 0, "hunger is back to 0");
                helper.assertTrue(record.state().dominion() == dominion, "dominion is unchanged");
                helper.assertTrue(record.state().staked().size() == staked.size()
                        && record.state().staked().containsAll(staked), "the chunks are unchanged");
                helper.assertTrue(staked.stream().allMatch(c -> data.getEmpireByChunk(c) == record), "and still the empire's");
                helper.assertTrue(data.getEmpireOf(player.getUUID()) == record, "the member stays");
                helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(SPOT)).getValue(BonfireBlock.LIT),
                        "a starving god does not put the fire out as it leaves");

                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
                interact(helper.getLevel(), helper.absolutePos(SPOT), player);
                helper.assertTrue(IRON_AUROCHS.equals(record.god()), "5 beef adopt a god again, god is " + record.god());
                helper.assertTrue(!record.currentDemand().isEmpty(), "which demands");
                helper.assertTrue(record.state().dominion() == dominion, "adoption leaves dominion as it is");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void withoutMeteodyneAYearIs72Days(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (ModList.get().isLoaded("meteodyne")) {
            // The core-less run's EmpireMeteodyneGameTests checks Meteodyne's year.
            helper.assertTrue(GodHunger.yearTicks(level) > 0, "a year has a length");
        } else {
            helper.assertTrue(GodHunger.yearTicks(level) == 1_728_000L, "the default year, is " + GodHunger.yearTicks(level));
            helper.assertTrue(GodHunger.limitTicks(level) == 1_728_000L, "a god waits one year by default");
        }
        helper.succeed();
    }

    /** The index of the demand line that asks for the god's founding (tier 1) meat, or -1. */
    private static int foundingMeatLine(EmpireRecord record) {
        GodDefinition god = Gods.get(record.god());
        List<DemandLine> lines = record.currentDemand();
        for (int i = 0; i < lines.size(); i++) {
            DemandLine line = lines.get(i);
            if (god.foundingMeat().stream().anyMatch(o -> o.sameAs(line))) {
                return i;
            }
        }
        return -1;
    }

    private static long chunkOf(BlockPos pos) {
        return ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private SatietyGameTests() {
    }
}
