// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianChunkLoading;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.civilian.ai.CivilianAiController;
import dev.brights0ng.empire.civilian.ai.CivilianAiController.InterruptKind;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.CivilianScheduleNbt;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianPreferences;
import dev.brights0ng.empire.rules.CivilianPreferences.Activity;
import dev.brights0ng.empire.rules.CivilianSchedule;
import dev.brights0ng.empire.rules.CivilianSchedule.Anchor;
import dev.brights0ng.empire.rules.CivilianSchedule.AnchorKind;
import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.empire.rules.FulfilmentModel.MeterReading;
import dev.brights0ng.empire.rules.FulfilmentModel.SourceKind;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.ScheduleClock;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Integration tests for Empire's 4-level Civilian AI (Schedule -> Slot -> Task -> Action), scheduled meals vs.
 * emergency hunger, liked-hobby Enjoyment credit, work assignment & route learning, live interrupts (hostile flee,
 * Meteodyne extreme temperature), D12 off-empire move-back, and record v4 persistence.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianAiGameTests {

    private static final String BATCH_CIVILIANS = "civilians";
    private static final long TEST_SCHEDULE_DAY = 20L;

    private static long dayTimeFor(long scheduleDay, int tickOfDay) {
        return scheduleDay * ScheduleClock.DAY_TICKS + ScheduleClock.MIDNIGHT_OFFSET + tickOfDay;
    }

    private static BlockPos mainPos(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(4, 2, 4));
    }

    private static EmpireRecord found(GameTestHelper helper, Player founder) {
        BlockPos main = mainPos(helper);
        return EmpireSavedData.of(helper.getLevel()).createEmpire(founder.getUUID(),
                ChunkKeys.pack(main.getX() >> 4, main.getZ() >> 4), main, helper.getLevel().getGameTime());
    }

    private static void disband(GameTestHelper helper, EmpireRecord empire) {
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        data.disbandEmpire(empire.id(), helper.getLevel().getServer());
        data.onBonfireRemoved(mainPos(helper), null);
    }

    private static CivilianEntity citizen(GameTestHelper helper, EmpireRecord empire, BlockPos pos) {
        CivilianEntity c = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(pos), null);
        c.setTraits(CivilianTraits.NEUTRAL);
        CivilianRoster.recruit(c, empire, helper.getLevel());
        return c;
    }

    private static void buildFloor(ServerLevel level, BlockPos center, int radius, List<BlockPos> placed) {
        int y = center.getY() - 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                placed.add(floor);
                for (int dy = 1; dy <= 3; dy++) {
                    BlockPos air = floor.above(dy);
                    if (!level.getBlockState(air).isAir()) {
                        level.setBlock(air, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private static void clearBlocks(ServerLevel level, List<BlockPos> placed) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos pos : placed) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_ai_meals", timeoutTicks = 130)
    public static void scheduledMealPrefersLikedFoodAndCitizenWaitsAboveEmergencyFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));

        // Start at 06:00 (well before Lunch ~09:00)
        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity c = citizen(helper, empire, main.offset(2, 0, 0));
        c.setPreferences(new CivilianPreferences(List.of("minecraft:bread"), List.of(Activity.READING)));
        c.setFood(14, 0.0F); // above emergency floor (10), fits apple (4) or bread (5)
        c.inventory().setItem(0, new ItemStack(Items.APPLE));
        c.inventory().setItem(1, new ItemStack(Items.BREAD));

        boolean[] heldFoodBeforeMeal = {true};
        helper.runAtTickTime(35, () -> {
            // Must NOT have grazed outside mealtime while food = 14 > emergency floor 10
            heldFoodBeforeMeal[0] = c.food().foodLevel() == 14 && c.inventory().countItem(Items.BREAD) == 1
                    && c.inventory().countItem(Items.APPLE) == 1;
            // Jump right to this citizen's scheduled Lunch anchor
            c.aiController().forceRebuildSchedule(level);
            int lunchStart = CivilianSchedule.BASE_LUNCH_TICK;
            for (Anchor a : c.schedule().anchors()) {
                if (a.kind() == AnchorKind.LUNCH) {
                    lunchStart = a.startTick();
                    break;
                }
            }
            level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, lunchStart));
        });

        helper.runAtTickTime(120, () -> {
            try {
                helper.assertTrue(heldFoodBeforeMeal[0], "citizen waited for scheduled meal instead of grazing at food=14");
                helper.assertTrue(c.inventory().countItem(Items.BREAD) == 0,
                        "scheduled meal preferred liked bread over slot-0 apple");
                helper.assertTrue(c.inventory().countItem(Items.APPLE) == 1, "unliked apple left uneaten once full");
                helper.assertTrue(c.food().foodLevel() >= 19, "food refilled by bread, got " + c.food().foodLevel());
                helper.assertTrue(c.fulfilment().likedMealsCount() == 1, "liked bread credited Enjoyment meals");
            } finally {
                level.setDayTime(savedTime);
                c.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_ai_work", timeoutTicks = 240)
    public static void assignedWorkAdvancesQuotaAndRecordsLearnedRoute(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 8, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity c = citizen(helper, empire, main.offset(2, 0, 0));
        BlockPos workSite = main.offset(-5, 0, 0);
        c.setWorkAssignment(WorkAssignment.of(workSite, false, ScheduleClock.mmss(7, 0), "potato_farm"));

        helper.runAtTickTime(210, () -> {
            try {
                helper.assertTrue(c.schedule().workDoneTicks() >= 40,
                        "citizen walked to work site and completed at least one work cycle, workDone="
                                + c.schedule().workDoneTicks());
                helper.assertTrue(!c.routeMemory().estimates().isEmpty(),
                        "walking to work recorded a learned route in RouteMemory");
                List<Component> report = c.aiController().formatScheduleReport(level);
                String joined = String.join("\n", report.stream().map(Component::getString).toList());
                helper.assertTrue(joined.contains("potato_farm"), "schedule report shows assigned work label");
            } finally {
                level.setDayTime(savedTime);
                c.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_ai_hobby", timeoutTicks = 120)
    public static void likedHobbyCreditsLiveEnjoymentAndTracksStaleness(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity c = citizen(helper, empire, main.offset(2, 0, 0));
        c.setPreferences(new CivilianPreferences(List.of("minecraft:bread"), List.of(Activity.READING)));
        c.aiController().forceRebuildSchedule(level);
        // Satisfy social quota so the gap-filler immediately picks liked hobby READING
        c.schedule().addSocialDoneTicks(c.schedule().socialQuotaTicks());

        helper.runAtTickTime(100, () -> {
            try {
                helper.assertTrue(c.fulfilment().likedHobbyTicksCount() > 0L,
                        "liked READING hobby credited live Enjoyment ticks, got " + c.fulfilment().likedHobbyTicksCount());
                MeterReading enjoyment = CivilianFulfilmentMeters.enjoymentMeter(c, 0.5);
                helper.assertTrue(enjoyment.source() == SourceKind.LIVE && enjoyment.value() > 0.0,
                        "Enjoyment meter is LIVE and positive: " + enjoyment);
                helper.assertTrue(Long.valueOf(TEST_SCHEDULE_DAY).equals(c.schedule().lastHobbyDays().get(Activity.READING)),
                        "lastHobbyDays recorded READING for today");
            } finally {
                level.setDayTime(savedTime);
                c.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_ai_flee", timeoutTicks = 60)
    public static void hostileDamageTriggersFleeInterrupt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity c = citizen(helper, empire, main.offset(4, 0, 0));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie created");
        zombie.setNoAi(true);
        zombie.setPos(main.getX() + 5.5, main.getY(), main.getZ() + 0.5);
        level.addFreshEntity(zombie);

        helper.runAtTickTime(5, () -> c.hurt(level.damageSources().mobAttack(zombie), 2.0F));
        helper.runAtTickTime(20, () -> {
            try {
                helper.assertTrue(c.aiController().activeInterrupt() == InterruptKind.FLEE,
                        "hostile damage activated FLEE interrupt, got " + c.aiController().activeInterrupt());
            } finally {
                level.setDayTime(savedTime);
                c.discard();
                zombie.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_ai_emergency_eat", timeoutTicks = 80)
    public static void emergencyHungerInterruptEatsOutsideMealtime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity c = citizen(helper, empire, main.offset(2, 0, 0));
        c.setFood(8, 0.0F); // below emergency floor (10)
        c.inventory().setItem(0, new ItemStack(Items.BREAD));

        helper.runAtTickTime(65, () -> {
            try {
                helper.assertTrue(c.food().foodLevel() >= 13,
                        "citizen ate emergency bread outside mealtime, food=" + c.food().foodLevel());
                helper.assertTrue(c.inventory().isEmpty(), "emergency bread consumed");
            } finally {
                level.setDayTime(savedTime);
                c.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_ai_thermal", timeoutTicks = 110)
    public static void extremeTemperatureInterruptSeeksShelterAndExitsOnWarmth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity c = citizen(helper, empire, main.offset(4, 0, 0));
        TemperatureProvider freezing = new TemperatureProvider() {
            @Override public double air(ServerLevel lvl, BlockPos pos) { return -20.0; }
            @Override public double room(ServerLevel lvl, Vec3 at) { return 0.0; }
        };
        TemperatureProvider comfortable = new TemperatureProvider() {
            @Override public double air(ServerLevel lvl, BlockPos pos) { return 20.0; }
            @Override public double room(ServerLevel lvl, Vec3 at) { return 0.0; }
        };
        c.aiController().setTemperatureProviderOverride(freezing);

        boolean[] enteredThermal = {false};
        helper.runAtTickTime(10, () -> {
            enteredThermal[0] = c.aiController().activeInterrupt() == InterruptKind.THERMAL_SHELTER;
            c.aiController().setTemperatureProviderOverride(comfortable);
        });
        helper.runAtTickTime(60, () -> {
            try {
                helper.assertTrue(enteredThermal[0], "freezing temp triggered THERMAL_SHELTER interrupt");
                helper.assertTrue(c.aiController().activeInterrupt() == InterruptKind.NONE,
                        "comfortable temp exited THERMAL_SHELTER, got " + c.aiController().activeInterrupt());
            } finally {
                c.aiController().setTemperatureProviderOverride(null);
                level.setDayTime(savedTime);
                c.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void offEmpireCitizenTeleportsBackToNearestStakedChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos main = mainPos(helper);
        // Move citizen 2 chunks east of mainPos (outside the 3x3 staked chunks)
        BlockPos outside = new BlockPos(((main.getX() >> 4) + 2) * 16 + 8, main.getY(), main.getZ());
        CivilianEntity c = citizen(helper, empire, outside);
        c.setNoAi(true);
        try {
            long beforeChunk = c.chunkPosition().toLong();
            helper.assertFalse(empire.state().staked().contains(beforeChunk), "citizen starts outside staked chunks");

            boolean moved = CivilianChunkLoading.reconcileOffEmpire(level);
            long afterChunk = c.chunkPosition().toLong();
            CivilianRecord record = EmpireSavedData.of(level).civilian(c.civilianId());
            helper.assertTrue(moved, "reconcileOffEmpire relocated off-empire citizen");
            helper.assertTrue(empire.state().staked().contains(afterChunk),
                    "citizen moved inside staked empire land: " + c.blockPosition());
            helper.assertTrue(record != null && empire.state().staked().contains(record.lastChunk()),
                    "master record lastChunk updated inside staked land");
            helper.succeed();
        } finally {
            c.discard();
            disband(helper, empire);
            CivilianChunkLoading.reconcileOffEmpire(level);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void scheduleRouteCrowdAndWorkPersistAcrossRecordAndReplacement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos main = mainPos(helper);
        CivilianEntity c = citizen(helper, empire, main);
        CivilianEntity replacement = null;
        try {
            BlockPos workPos = main.offset(5, 0, 5);
            c.setWorkAssignment(WorkAssignment.of(workPos, true, ScheduleClock.mmss(6, 30), "bakery"));
            c.aiController().forceRebuildSchedule(level);
            c.schedule().addWorkDoneTicks(600);
            c.schedule().recordHobbyPerformed(Activity.READING, 12L);
            c.routeMemory().recordTrip(LocationKey.HOME, LocationKey.WORK, 340, 300);
            c.crowdMemory().recordSighting(LocationKey.FOOD_SITE, 12L, ScheduleClock.mmss(9, 15));
            c.syncNeedsToRecord();

            CivilianRecord record = EmpireSavedData.of(level).civilian(c.civilianId());
            helper.assertTrue(record != null && CivilianRecord.VERSION == 5, "record v5 present");
            CompoundTag savedTag = record.save(level.registryAccess());
            CivilianRecord reloaded = CivilianRecord.load(savedTag, level.registryAccess());

            helper.assertTrue(reloaded.schedule().work() != null && "bakery".equals(reloaded.schedule().work().label()),
                    "work assignment survives record NBT");
            helper.assertTrue(reloaded.schedule().schedule().workDoneTicks() == 600,
                    "workDoneTicks survives record NBT");
            helper.assertTrue(reloaded.schedule().routes().estimates().size() == 1,
                    "RouteMemory survives record NBT");
            helper.assertTrue(reloaded.schedule().crowd().observedLunchTick() == ScheduleClock.mmss(9, 15),
                    "CrowdMemory lunch peak survives record NBT");

            // Legacy record without schedule key loads safely
            savedTag.remove("schedule");
            CivilianRecord legacy = CivilianRecord.load(savedTag, level.registryAccess());
            helper.assertTrue(legacy.schedule().equals(CivilianScheduleNbt.SavedBundle.EMPTY),
                    "legacy record missing schedule key loads empty defaults");

            // Replacement body restores schedule bundle cleanly
            c.discard();
            replacement = CivilianSpawning.respawnBody(reloaded, level);
            helper.assertTrue(replacement.workAssignment() != null && "bakery".equals(replacement.workAssignment().label()),
                    "replacement body preserves WorkAssignment");
            helper.assertTrue(replacement.schedule().workDoneTicks() == 600,
                    "replacement body preserves schedule workDoneTicks");
            helper.assertTrue(replacement.routeMemory().learnedTicks(LocationKey.HOME, LocationKey.WORK) == 340,
                    "replacement body preserves RouteMemory");
            helper.succeed();
        } finally {
            c.discard();
            if (replacement != null) replacement.discard();
            disband(helper, empire);
        }
    }

    private CivilianAiGameTests() {
    }
}
