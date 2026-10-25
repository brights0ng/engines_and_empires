// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.civilian.TravellerArrivals;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ArrivalRule;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.TravellerWants;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The civilian roster: records as the master copy of their bodies, death, duplicate and orphan bodies, lost bodies,
 * homes following the main bonfire, the traveller cap, accepting a traveller with food, and disbanding. Each test founds
 * an empire on its own area's chunk and disbands it at the end; all run synchronously, so no two empires overlap.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianRosterGameTests {

    private static final String BATCH = "civilians";

    private static EmpireRecord found(GameTestHelper helper, UUID founder) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return EmpireSavedData.of(helper.getLevel()).createEmpire(founder,
                ChunkKeys.pack(origin.getX() >> 4, origin.getZ() >> 4), mainPos(helper), helper.getLevel().getGameTime());
    }

    private static BlockPos mainPos(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(1, 1, 1));
    }

    private static UUID founder(GameTestHelper helper) {
        return helper.makeMockPlayer(GameType.SURVIVAL).getUUID();
    }

    private static void disband(GameTestHelper helper, EmpireRecord record, MinecraftServer server) {
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        data.disbandEmpire(record.id(), server);
        data.onBonfireRemoved(mainPos(helper), null);
    }

    private static CivilianEntity citizen(GameTestHelper helper, EmpireRecord record) {
        CivilianEntity civilian = CivilianSpawning.spawn(helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))), null);
        CivilianRoster.recruit(civilian, record, helper.getLevel());
        return civilian;
    }

    private static String name(Entity entity) {
        return entity.getCustomName() != null ? entity.getCustomName().getString() : null;
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void recruitCreatesRecordAndCounts(GameTestHelper helper) {
        EmpireRecord record = found(helper, founder(helper));
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        CivilianEntity civilian = citizen(helper, record);
        try {
            helper.assertTrue(civilian.status() == CivilianEntity.Status.CITIZEN, "a citizen, got " + civilian.status());
            CivilianRecord civ = data.civilian(civilian.civilianId());
            helper.assertTrue(civ != null, "the citizen has a record");
            helper.assertTrue(data.citizenCount(record.id()) == 1, "1 citizen, got " + data.citizenCount(record.id()));
            helper.assertTrue(civ.body().equals(civilian.getUUID()), "the record's body is the entity");
            helper.assertTrue(civ.name().equals(name(civilian)), "name matches: " + civ.name() + " vs " + name(civilian));
            helper.assertTrue(civ.appearance() == civilian.appearance().pack(), "look matches");
            helper.assertTrue(mainPos(helper).equals(civilian.home()), "home is the main bonfire, got " + civilian.home());
        } finally {
            civilian.discard();
            disband(helper, record, null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void citizenDeathDeletesRecord(GameTestHelper helper) {
        EmpireRecord record = found(helper, founder(helper));
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        CivilianEntity civilian = citizen(helper, record);
        UUID id = civilian.civilianId();
        try {
            civilian.kill();
            helper.assertTrue(data.civilian(id) == null, "a dead citizen's record is deleted");
            helper.assertTrue(data.citizenCount(record.id()) == 0, "no citizens left");
        } finally {
            civilian.discard();
            disband(helper, record, null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void duplicateBodyIsCancelled(GameTestHelper helper) {
        EmpireRecord record = found(helper, founder(helper));
        CivilianEntity civilian = citizen(helper, record);
        try {
            CompoundTag tag = civilian.saveWithoutId(new CompoundTag());
            CivilianEntity copy = EmpireContent.CIVILIAN.get().create(helper.getLevel());
            helper.assertTrue(copy != null, "copy created");
            copy.load(tag);
            copy.setUUID(UUID.randomUUID());
            helper.assertTrue(copy.status() == CivilianEntity.Status.CITIZEN, "the copy is a citizen");
            helper.assertFalse(helper.getLevel().addFreshEntity(copy), "a second body of the same record is refused");
            helper.assertTrue(civilian.isAlive() && !civilian.isRemoved(), "the real body stays");
        } finally {
            civilian.discard();
            disband(helper, record, null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void orphanBodyIsCancelled(GameTestHelper helper) {
        EmpireRecord record = found(helper, founder(helper));
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        CivilianEntity civilian = citizen(helper, record);
        try {
            CompoundTag tag = civilian.saveWithoutId(new CompoundTag());
            civilian.discard();
            data.removeCivilian(civilian.civilianId());
            CivilianEntity reloaded = EmpireContent.CIVILIAN.get().create(helper.getLevel());
            helper.assertTrue(reloaded != null, "body created");
            reloaded.load(tag);
            helper.assertFalse(helper.getLevel().addFreshEntity(reloaded), "a body with no record is refused");
        } finally {
            disband(helper, record, null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void missingBodyRespawns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord record = found(helper, founder(helper));
        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianEntity civilian = citizen(helper, record);
        CivilianRecord civ = data.civilian(civilian.civilianId());
        UUID oldBody = civilian.getUUID();
        Entity respawned = null;
        try {
            civilian.discard();
            for (int i = 0; i < CivilianRoster.MISSING_STRIKES; i++) {
                helper.assertTrue(civ.body().equals(oldBody), "no new body before strike " + (i + 1));
                CivilianRoster.runMissingCheck(level);
            }
            helper.assertTrue(data.civilian(civ.id()) == civ, "a lost body is not a death");
            helper.assertFalse(civ.body().equals(oldBody), "the record names a new body");
            respawned = level.getEntity(civ.body());
            helper.assertTrue(respawned instanceof CivilianEntity, "the new body is in the level");
            CivilianEntity body = (CivilianEntity) respawned;
            helper.assertTrue(body.status() == CivilianEntity.Status.CITIZEN, "the new body is a citizen");
            helper.assertTrue(civ.id().equals(body.civilianId()), "the new body has the record's id");
            helper.assertTrue(civ.name().equals(name(body)), "same name: " + name(body));
            helper.assertTrue(civ.appearance() == body.appearance().pack(), "same look");
        } finally {
            if (respawned != null) {
                respawned.discard();
            }
            disband(helper, record, null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void homeFollowsMainBonfire(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        EmpireRecord record = found(helper, founder(helper));
        CivilianEntity civilian = citizen(helper, record);
        BlockPos second = mainPos(helper).above(2);
        try {
            helper.assertTrue(mainPos(helper).equals(civilian.home()), "home starts at the main bonfire");
            data.onBonfirePlaced(second, server);
            data.onBonfireRemoved(mainPos(helper), server);
            helper.assertTrue(second.equals(record.mainBonfire()), "the second bonfire is main: " + record.mainBonfire());
            helper.assertTrue(second.equals(civilian.home()), "the citizen's home follows it, got " + civilian.home());
            helper.assertTrue(second.equals(civilian.getRestrictCenter()), "and its restriction");
        } finally {
            civilian.discard();
            data.onBonfireRemoved(second, null);
            disband(helper, record, null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void noArrivalAtThreeCitizens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level);
        EmpireRecord record = found(helper, founder(helper));
        try {
            for (int i = 0; i < ArrivalRule.PIONEER_LIMIT; i++) {
                // Bodies that never existed, last seen in a chunk far away (never loaded), so none is respawned.
                data.addCivilian(new CivilianRecord(UUID.randomUUID(), record.id(), UUID.randomUUID(), "Pioneer " + i, 0,
                        BlockPos.ZERO, ChunkKeys.pack(100_000, 100_000), level.getGameTime()));
            }
            helper.assertTrue(data.citizenCount(record.id()) == 3, "3 citizens");
            helper.assertFalse(ArrivalRule.canArrive(true, true, data.citizenCount(record.id()),
                    data.traveller(record.id()) != null, level.getGameTime(), record.nextTravellerTime()),
                    "no arrival at 3 citizens");
            TravellerArrivals.Forced forced = TravellerArrivals.force(record, level);
            helper.assertTrue(forced.traveller() == null, "the forced path spawns nobody");
            helper.assertTrue(forced.failure() != null && forced.failure().contains("3/3"),
                    "the forced path reports the cap, got " + forced.failure());
        } finally {
            disband(helper, record, null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void acceptWithWantedFoodRecruits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireSavedData data = EmpireSavedData.of(level);
        Player member = helper.makeMockPlayer(GameType.SURVIVAL);
        Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord record = found(helper, member.getUUID());
        CivilianEntity traveller = TravellerArrivals.spawnTraveller(record, level, helper.absolutePos(new BlockPos(2, 1, 2)));
        try {
            helper.assertTrue(traveller.status() == CivilianEntity.Status.TRAVELLER, "a traveller");
            helper.assertTrue(data.traveller(record.id()) != null && data.traveller(record.id()).body().equals(traveller.getUUID()),
                    "the slot names it");
            TravellerWants.Want want = TravellerWants.WANTS.get(traveller.want());

            ItemStack few = new ItemStack(CivilianRoster.item(want), want.count() - 1);
            member.setItemInHand(InteractionHand.MAIN_HAND, few);
            traveller.interact(member, InteractionHand.MAIN_HAND);
            helper.assertTrue(traveller.status() == CivilianEntity.Status.TRAVELLER, "too few: still a traveller");
            helper.assertTrue(few.getCount() == want.count() - 1, "too few: nothing consumed");

            ItemStack strangers = new ItemStack(CivilianRoster.item(want), want.count());
            stranger.setItemInHand(InteractionHand.MAIN_HAND, strangers);
            traveller.interact(stranger, InteractionHand.MAIN_HAND);
            helper.assertTrue(traveller.status() == CivilianEntity.Status.TRAVELLER, "a non-member: still a traveller");
            helper.assertTrue(strangers.getCount() == want.count(), "a non-member: nothing consumed");

            ItemStack exact = new ItemStack(CivilianRoster.item(want), want.count());
            member.setItemInHand(InteractionHand.MAIN_HAND, exact);
            traveller.interact(member, InteractionHand.MAIN_HAND);
            helper.assertTrue(traveller.status() == CivilianEntity.Status.CITIZEN, "accepted: a citizen, got " + traveller.status());
            helper.assertTrue(member.getMainHandItem().isEmpty(), "accepted: the stack is used up");
            helper.assertTrue(traveller.civilianId() != null && data.civilian(traveller.civilianId()) != null, "accepted: a record");
            helper.assertTrue(data.traveller(record.id()) == null, "accepted: the slot is cleared");
        } finally {
            traveller.discard();
            disband(helper, record, null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void disbandMakesCitizensLeave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        EmpireRecord record = found(helper, founder(helper));
        CivilianEntity civilian = citizen(helper, record);
        UUID id = civilian.civilianId();
        try {
            disband(helper, record, server);
            helper.assertTrue(civilian.status() == CivilianEntity.Status.LEAVING, "the body leaves, got " + civilian.status());
            helper.assertTrue(data.civilian(id) == null, "the record is gone");
            helper.assertTrue(data.citizenCount(record.id()) == 0, "no citizens left");
        } finally {
            civilian.discard();
        }
        helper.succeed();
    }

    private CivilianRosterGameTests() {
    }
}
