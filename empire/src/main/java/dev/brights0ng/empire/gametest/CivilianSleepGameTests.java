// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.CivilianSleepNbt;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianAbilities;
import dev.brights0ng.empire.rules.CivilianSleep;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Civilian sleep game tests: bed auto-claiming, POI release, persistence and wake-up interactions run in batch
 * {@code civilians}; tests that change world day time run in batch {@code civilian_sleep} and restore time in
 * {@code finally}.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianSleepGameTests {

    private static final String BATCH_CIVILIANS = "civilians";
    private static final CivilianAbilities AVG_ABILITIES = new CivilianAbilities(10, 10, 10, 10, 10, 10);
    private static final long TEST_NIGHT = 10L;

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
        CivilianEntity civilian = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(pos), null);
        civilian.setAbilities(AVG_ABILITIES);
        CivilianRoster.recruit(civilian, empire, helper.getLevel());
        return civilian;
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

    /**
     * Builds an enclosed, lit stone room whose interior spans {@code [origin, origin + (w-1, h-1, d-1)]} with a torch
     * at the centre of the floor. Returns the interior origin.
     */
    private static BlockPos buildHomeRoom(ServerLevel level, BlockPos origin, int innerW, int innerH, int innerD,
                                          List<BlockPos> placed) {
        for (int dx = -1; dx <= innerW; dx++) {
            for (int dy = -1; dy <= innerH; dy++) {
                for (int dz = -1; dz <= innerD; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    boolean shell = dx == -1 || dx == innerW || dy == -1 || dy == innerH || dz == -1 || dz == innerD;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(p, state, Block.UPDATE_ALL);
                    placed.add(p);
                }
            }
        }
        BlockPos torch = origin.offset(innerW / 2, 0, innerD / 2);
        level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        placed.add(torch);
        return origin;
    }

    /** Places a complete bed without premature neighbour breakage; returns the head BlockPos. */
    private static BlockPos placeBed(ServerLevel level, BlockPos foot, Direction facing, List<BlockPos> placed) {
        BlockPos head = foot.relative(facing);
        BlockState footState = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.OCCUPIED, false);
        BlockState headState = footState.setValue(BedBlock.PART, BedPart.HEAD);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(foot, footState, flags);
        level.setBlock(head, headState, flags);
        placed.add(foot);
        placed.add(head);
        return head;
    }

    private static void clearBlocks(ServerLevel level, List<BlockPos> placed) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos pos : placed) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_claims", timeoutTicks = 20)
    public static void claimsNearestBedOnStakedLandAndIgnoresUnstaked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));

        BlockPos nearRoom = buildHomeRoom(level, main.offset(2, 0, -2), 5, 2, 5, placed);
        BlockPos nearBed = placeBed(level, nearRoom.offset(0, 0, 2), Direction.EAST, placed);
        BlockPos farRoom = buildHomeRoom(level, main.offset(-8, 0, -2), 5, 2, 5, placed);
        BlockPos farBed = placeBed(level, farRoom.offset(4, 0, 2), Direction.WEST, placed);
        // Unstaked chunk: 2 chunks east of mainPos (empire only stakes a 3x3 chunk area: dx in [-1, 1]).
        BlockPos outsideOrigin = new BlockPos(((main.getX() >> 4) + 2) * 16 + 2, main.getY(), main.getZ());
        buildHomeRoom(level, outsideOrigin, 5, 2, 5, placed);
        placeBed(level, outsideOrigin.offset(0, 0, 2), Direction.EAST, placed);

        CivilianEntity first = citizen(helper, empire, main.offset(0, 0, 1));
        CivilianEntity second = citizen(helper, empire, main.offset(0, 0, -1));
        CivilianEntity third = citizen(helper, empire, main.offset(-1, 0, 1));

        // Wait 1 tick for ServerLevel.onBlockStateChange's server.execute POI add.
        helper.runAtTickTime(3, () -> {
            try {
                BlockPos claimed1 = CivilianBeds.ensureOrClaimBed(level, empire, first);
                BlockPos claimed2 = CivilianBeds.ensureOrClaimBed(level, empire, second);
                BlockPos claimed3 = CivilianBeds.ensureOrClaimBed(level, empire, third);

                helper.assertTrue(nearBed.equals(claimed1), "first citizen claims nearest bed: " + claimed1);
                helper.assertTrue(farBed.equals(claimed2), "second citizen claims remaining staked bed: " + claimed2);
                helper.assertTrue(claimed3 == null, "third citizen never claims unstaked bed: " + claimed3);
            } finally {
                first.discard();
                second.discard();
                third.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_poi_release", timeoutTicks = 20)
    public static void bedPoiReleasedOnDeathAndDisband(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 4, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos room = buildHomeRoom(level, main.offset(2, 0, -2), 5, 2, 5, placed);
        BlockPos bedHead = placeBed(level, room.offset(0, 0, 2), Direction.EAST, placed);

        CivilianEntity first = citizen(helper, empire, main.offset(-1, 0, 1));
        CivilianEntity second = citizen(helper, empire, main.offset(-1, 0, -1));

        helper.runAtTickTime(3, () -> {
            try {
                BlockPos claimed = CivilianBeds.ensureOrClaimBed(level, empire, first);
                helper.assertTrue(bedHead.equals(claimed), "first claimed bed");
                helper.assertTrue(level.getPoiManager().getFreeTickets(bedHead) == 0, "POI ticket acquired");

                first.kill();
                helper.assertTrue(level.getPoiManager().getFreeTickets(bedHead) == 1, "death releases POI ticket");

                BlockPos reclaimed = CivilianBeds.ensureOrClaimBed(level, empire, second);
                helper.assertTrue(bedHead.equals(reclaimed), "second citizen can claim released bed");
                helper.assertTrue(level.getPoiManager().getFreeTickets(bedHead) == 0, "POI ticket re-acquired");

                disband(helper, empire);
                helper.assertTrue(level.getPoiManager().getFreeTickets(bedHead) == 1, "disband releases POI ticket");
            } finally {
                first.discard();
                second.discard();
                if (EmpireSavedData.of(level).getEmpire(empire.id()) != null) {
                    disband(helper, empire);
                }
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void sleepAndBedPersistAcrossRecordAndReplacement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = citizen(helper, empire, mainPos(helper));
        CivilianEntity replacement = null;
        try {
            BlockPos fakeBed = mainPos(helper).offset(2, 0, 2);
            civilian.setClaimedBed(fakeBed);
            civilian.setLastNight(0.75F);

            CivilianRecord record = EmpireSavedData.of(level).civilian(civilian.civilianId());
            helper.assertTrue(record != null, "record exists");
            helper.assertTrue(fakeBed.equals(record.bed()), "bed wrote through to record");
            helper.assertTrue(Math.abs(record.sleep().lastNight() - 0.75F) < 1e-5F, "lastNight wrote through");

            CompoundTag savedTag = record.save(level.registryAccess());
            CivilianRecord reloaded = CivilianRecord.load(savedTag, level.registryAccess());
            helper.assertTrue(fakeBed.equals(reloaded.bed()), "bed survives NBT round-trip");
            helper.assertTrue(Math.abs(reloaded.sleep().lastNight() - 0.75F) < 1e-5F, "sleep survives NBT round-trip");

            // Corrupt sleep NBT degrades safely.
            CompoundTag corrupt = new CompoundTag();
            corrupt.putInt("slept_ticks", -50);
            corrupt.putFloat("credit_ticks", Float.NaN);
            corrupt.putFloat("last_night", 5.0F);
            CivilianSleep.Saved clamped = CivilianSleepNbt.load(corrupt);
            helper.assertTrue(clamped.sleptTicks() == 0 && clamped.creditTicks() == 0.0F && clamped.lastNight() == 1.0F,
                    "corrupt sleep NBT bounded safely");

            civilian.discard();
            replacement = CivilianSpawning.respawnBody(record, level);
            helper.assertTrue(fakeBed.equals(replacement.claimedBed()), "replacement keeps claimed bed");
            helper.assertTrue(Math.abs(replacement.sleep().lastNight() - 0.75F) < 1e-5F, "replacement keeps lastNight");
        } finally {
            civilian.discard();
            if (replacement != null) replacement.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void sneakInspectDoesNotWakeAndNormalClickWakesWithCooldown(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = citizen(helper, empire, mainPos(helper));
        try {
            civilian.startRoughSleeping();
            helper.assertTrue(civilian.isLyingDown() && civilian.isRoughSleeping(), "civilian lying down rough");

            Player sneaking = helper.makeMockPlayer(GameType.SURVIVAL);
            sneaking.setShiftKeyDown(true);
            civilian.interact(sneaking, InteractionHand.MAIN_HAND);
            helper.assertTrue(civilian.isLyingDown(), "sneak inspect does not wake sleeping civilian");

            Player clicking = helper.makeMockPlayer(GameType.SURVIVAL);
            civilian.interact(clicking, InteractionHand.MAIN_HAND);
            helper.assertFalse(civilian.isLyingDown(), "normal right-click wakes civilian");
            helper.assertTrue(civilian.isSleepWakeCooldown(), "waking sets cooldown");
        } finally {
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_damage", timeoutTicks = 20)
    public static void damageWakesBedAndRoughSleeperAndOccupiedBedRejectsPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 4, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos room = buildHomeRoom(level, main.offset(2, 0, -2), 5, 2, 5, placed);
        BlockPos bedHead = placeBed(level, room.offset(3, 0, 2), Direction.EAST, placed);
        CivilianEntity civilian = citizen(helper, empire, room.offset(1, 0, 2));

        helper.runAtTickTime(3, () -> {
            try {
                // Rough sleep damage wake.
                civilian.startRoughSleeping();
                civilian.hurt(level.damageSources().generic(), 1.0F);
                helper.assertFalse(civilian.isLyingDown(), "damage wakes rough sleeper");
                helper.assertTrue(civilian.isSleepWakeCooldown(), "rough damage wake sets cooldown");

                // Bed sleep: player click does not kick civilian; damage wakes and clears OCCUPIED.
                civilian.startSleeping(bedHead);
                helper.assertTrue(civilian.isSleeping(), "civilian sleeping in bed");
                helper.assertTrue(level.getBlockState(bedHead).getValue(BedBlock.OCCUPIED), "bed marked occupied");

                Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bedHead), Direction.UP, bedHead, false);
                level.getBlockState(bedHead).useWithoutItem(level, player, hit);
                helper.assertTrue(civilian.isSleeping(), "player clicking occupied bed does not kick civilian out");

                civilian.hurt(level.damageSources().generic(), 1.0F);
                helper.assertFalse(civilian.isSleeping(), "damage wakes bed sleeper");
                helper.assertFalse(level.getBlockState(bedHead).getValue(BedBlock.OCCUPIED), "bed OCCUPIED cleared");
                helper.assertTrue(civilian.isSleepWakeCooldown(), "bed damage wake sets cooldown");
            } finally {
                civilian.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_bed_goal", timeoutTicks = 80)
    public static void bedSleepGoalAndRestedCompletion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 5, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos room = buildHomeRoom(level, main.offset(2, 0, -2), 5, 2, 5, placed);
        BlockPos bedHead = placeBed(level, room.offset(3, 0, 2), Direction.EAST, placed);
        CivilianEntity civilian = citizen(helper, empire, room.offset(1, 0, 2));

        int need = civilian.sleepNeedTicks();
        long bedtime = CivilianSleep.ownBedtime(TEST_NIGHT, need);
        level.setDayTime(TEST_NIGHT * CivilianSleep.DAY_LENGTH + 1000L);
        helper.runAtTickTime(1, () -> level.setDayTime(bedtime));

        boolean[] sleptInBed = {false};
        boolean[] jumped = {false};
        helper.onEachTick(() -> {
            if (civilian.isSleeping()) {
                sleptInBed[0] = true;
                if (!jumped[0]) {
                    jumped[0] = true;
                    // Jump near the end of the sleep period so the last few ticks finish live and wake rested (R6).
                    level.setDayTime(CivilianSleep.ownWakeTime(TEST_NIGHT, need) - 5L);
                }
            }
        });
        helper.runAtTickTime(70, () -> {
            try {
                helper.assertTrue(sleptInBed[0], "citizen walked to bed and slept at bedtime");
                helper.assertTrue(bedHead.equals(civilian.claimedBed()), "bed was auto-claimed");
                helper.assertFalse(civilian.isLyingDown(), "citizen woke up rested");
                helper.assertTrue(civilian.sleep().isRestedFor(TEST_NIGHT), "citizen marked rested");
                helper.assertTrue(Math.abs(civilian.sleep().lastNight() - 1.0F) < 1e-4F,
                        "bed sleep finalises at 1.0, got " + civilian.sleep().lastNight());
            } finally {
                level.setDayTime(savedTime);
                civilian.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_rough_goal", timeoutTicks = 120)
    public static void roughSleepByBonfireCapsAtHalf(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = citizen(helper, empire, main.offset(2, 0, 0));

        int need = civilian.sleepNeedTicks();
        level.setDayTime(CivilianSleep.ownBedtime(TEST_NIGHT, need));

        boolean[] sawRough = {false};
        boolean[] jumped = {false};
        helper.onEachTick(() -> {
            if (civilian.isRoughSleeping()) {
                sawRough[0] = true;
                if (!jumped[0]) {
                    jumped[0] = true;
                    // Jump near the end of the sleep period so the remaining ticks finish live to exact rested cap.
                    level.setDayTime(CivilianSleep.ownWakeTime(TEST_NIGHT, need) - 5L);
                }
            }
        });
        helper.runAtTickTime(100, () -> {
            try {
                helper.assertTrue(sawRough[0], "bedless citizen lay down for rough sleep");
                helper.assertFalse(civilian.isLyingDown(), "citizen woke up rested");
                helper.assertTrue(civilian.sleep().isRestedFor(TEST_NIGHT), "citizen marked rested");
                helper.assertTrue(Math.abs(civilian.sleep().lastNight() - 0.5F) < 1e-4F,
                        "rough sleep caps at 0.5, got " + civilian.sleep().lastNight());
            } finally {
                level.setDayTime(savedTime);
                civilian.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_occupied", timeoutTicks = 80)
    public static void occupiedBedFallsBackToRoughWithoutReleasingClaim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos room = buildHomeRoom(level, main.offset(5, 0, -2), 5, 2, 5, placed);
        BlockPos bedHead = placeBed(level, room.offset(3, 0, 2), Direction.EAST, placed);
        CivilianEntity civilian = citizen(helper, empire, main.offset(-2, 0, 2));
        level.setDayTime(TEST_NIGHT * CivilianSleep.DAY_LENGTH + 1000L);

        helper.runAtTickTime(3, () -> {
            CivilianBeds.ensureOrClaimBed(level, empire, civilian);
            // Occupy the bed externally (e.g., player sleeping in it).
            level.setBlock(bedHead, level.getBlockState(bedHead).setValue(BedBlock.OCCUPIED, true),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            level.setDayTime(CivilianSleep.ownBedtime(TEST_NIGHT, civilian.sleepNeedTicks()));
        });
        helper.runAtTickTime(70, () -> {
            try {
                helper.assertTrue(civilian.isRoughSleeping(), "falls back to rough sleep when bed is occupied");
                helper.assertFalse(civilian.isSleeping(), "not in occupied bed");
                helper.assertTrue(bedHead.equals(civilian.claimedBed()), "bed claim is retained");
            } finally {
                level.setDayTime(savedTime);
                civilian.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_night_skip", timeoutTicks = 20)
    public static void nightSkipTeleportsAwakeCitizensAndLeavesSleepersInPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos room = buildHomeRoom(level, main.offset(4, 0, -2), 5, 2, 5, placed);
        BlockPos bedHead = placeBed(level, room.offset(0, 0, 2), Direction.EAST, placed);

        // Keep all three citizens inside main's chunk so every entity ticks without waiting for chunk-loading recompute.
        BlockPos claimsStart = new BlockPos((main.getX() & ~15) + 1, main.getY(), (main.getZ() & ~15) + 1);
        BlockPos roughStart = new BlockPos((main.getX() & ~15) + 1, main.getY(), (main.getZ() & ~15) + 14);
        BlockPos sleeperPos = new BlockPos((main.getX() & ~15) + 14, main.getY(), (main.getZ() & ~15) + 14);
        for (BlockPos p : List.of(claimsStart, roughStart, sleeperPos)) {
            level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            placed.add(p.below());
        }
        CivilianEntity claimsOnSkip = citizen(helper, empire, claimsStart);
        claimsOnSkip.setNoAi(true);
        CivilianEntity roughOnSkip = citizen(helper, empire, roughStart);
        roughOnSkip.setNoAi(true);
        CivilianEntity alreadyLying = citizen(helper, empire, sleeperPos);
        alreadyLying.setNoAi(true);

        long earlyNight = TEST_NIGHT * CivilianSleep.DAY_LENGTH + 13_000L;
        level.setDayTime(earlyNight);

        helper.runAtTickTime(2, () -> {
            alreadyLying.startRoughSleeping();
            // Advance day time past night end (simulating players sleeping through the night).
            level.setDayTime((TEST_NIGHT + 1) * CivilianSleep.DAY_LENGTH);
        });
        helper.runAtTickTime(4, () -> {
            try {
                helper.assertTrue(bedHead.equals(claimsOnSkip.claimedBed()), "night skip claimed free bed");
                helper.assertTrue(bedHead.closerToCenterThan(claimsOnSkip.position(), 3.0),
                        "bed citizen teleported to bed, at " + claimsOnSkip.blockPosition());
                helper.assertTrue(Math.abs(claimsOnSkip.sleep().lastNight() - 1.0F) < 1e-4F, "bed skip credited 1.0");

                helper.assertTrue(roughOnSkip.claimedBed() == null, "second citizen had no bed");
                helper.assertTrue(main.closerToCenterThan(roughOnSkip.position(), 6.0),
                        "bedless citizen teleported near bonfire, at " + roughOnSkip.blockPosition());
                helper.assertTrue(Math.abs(roughOnSkip.sleep().lastNight() - 0.5F) < 1e-4F, "rough skip credited 0.5");

                helper.assertTrue(sleeperPos.closerToCenterThan(alreadyLying.position(), 1.5),
                        "already lying down civilian stayed put, at " + alreadyLying.blockPosition());
                helper.assertFalse(alreadyLying.isLyingDown(), "sleeper woke up after night skip");
                helper.assertTrue(Math.abs(alreadyLying.sleep().lastNight() - 0.5F) < 1e-4F, "sleeper credited 0.5");
            } finally {
                level.setDayTime(savedTime);
                claimsOnSkip.discard();
                roughOnSkip.discard();
                alreadyLying.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_time_add", timeoutTicks = 20)
    public static void timeAddWithinNightCreditsWithoutTeleport(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos room = buildHomeRoom(level, main.offset(4, 0, -2), 5, 2, 5, placed);
        placeBed(level, room.offset(0, 0, 2), Direction.EAST, placed);

        BlockPos startPos = new BlockPos((main.getX() & ~15) + 1, main.getY(), (main.getZ() & ~15) + 1);
        level.setBlock(startPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        placed.add(startPos.below());
        CivilianEntity civilian = citizen(helper, empire, startPos);
        civilian.setNoAi(true);
        long bedtime = CivilianSleep.ownBedtime(TEST_NIGHT, civilian.sleepNeedTicks());
        level.setDayTime(bedtime);

        helper.runAtTickTime(3, () -> {
            CivilianBeds.ensureOrClaimBed(level, empire, civilian);
            // Jump halfway through sleep period, still inside night.
            level.setDayTime(level.getDayTime() + 3600L);
        });
        helper.runAtTickTime(5, () -> {
            try {
                helper.assertTrue(startPos.closerToCenterThan(civilian.position(), 1.5),
                        "within-night jump did not teleport citizen");
                helper.assertTrue(civilian.sleep().hasOpenSession(), "session remains open within night");
                helper.assertTrue(civilian.sleep().sleptTicks() >= 3599, "half sleep period credited: " + civilian.sleep().sleptTicks());
                helper.assertTrue(Float.isNaN(civilian.sleep().lastNight()), "not finalised mid-night");
            } finally {
                level.setDayTime(savedTime);
                civilian.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_sleep_unloaded", timeoutTicks = 20)
    public static void unloadedCatchUpCreditsWithoutTeleportAndAwakeAllNightIsZero(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        buildFloor(level, main, 5, placed);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos room = buildHomeRoom(level, main.offset(3, 0, -2), 5, 2, 5, placed);
        BlockPos bedHead = placeBed(level, room.offset(0, 0, 2), Direction.EAST, placed);

        // Unloaded catch-up: morning of day 11 after last seeing day 10 morning.
        long day11Morning = (TEST_NIGHT + 1) * CivilianSleep.DAY_LENGTH + 1000L;
        level.setDayTime(day11Morning);

        BlockPos startPos = main.offset(-3, 0, 0);
        CivilianEntity withBed = citizen(helper, empire, startPos);
        withBed.setNoAi(true);
        CivilianEntity noBed = citizen(helper, empire, startPos);
        noBed.setNoAi(true);
        CivilianEntity awakeAllNight = citizen(helper, empire, startPos);
        awakeAllNight.setNoAi(true);
        CivilianEntity freeCivilian = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main.offset(0, 0, 3)), null);

        helper.runAtTickTime(3, () -> {
            try {
                // Simulate reload from disk with old lastDayTime on fresh replacement bodies.
                CivilianBeds.ensureOrClaimBed(level, empire, withBed);
                CivilianRecord bedRec = EmpireSavedData.of(level).civilian(withBed.civilianId());
                CivilianRecord noBedRec = EmpireSavedData.of(level).civilian(noBed.civilianId());
                // Discard before writing the older lastDayTime so onRemovedFromLevel's sync does not overwrite it.
                withBed.discard();
                noBed.discard();
                long day10Morning = TEST_NIGHT * CivilianSleep.DAY_LENGTH + 1000L;
                bedRec.setSleep(new CivilianSleep.Saved(CivilianSleep.NO_NIGHT, CivilianSleep.NO_NIGHT, 0, 0.0F, Float.NaN, day10Morning));
                noBedRec.setSleep(new CivilianSleep.Saved(CivilianSleep.NO_NIGHT, CivilianSleep.NO_NIGHT, 0, 0.0F, Float.NaN, day10Morning));

                CivilianEntity reloadedBed = CivilianSpawning.respawnBody(bedRec, level);
                reloadedBed.setNoAi(true);
                reloadedBed.setPos(startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5);
                CivilianEntity reloadedRough = CivilianSpawning.respawnBody(noBedRec, level);
                reloadedRough.setNoAi(true);
                reloadedRough.setPos(startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5);

                reloadedBed.aiStep();
                reloadedRough.aiStep();

                helper.assertTrue(startPos.closerToCenterThan(reloadedBed.position(), 1.5), "unloaded catch-up never teleports");
                helper.assertTrue(bedHead.equals(reloadedBed.claimedBed()), "reloaded citizen keeps bed");
                helper.assertTrue(Math.abs(reloadedBed.sleep().lastNight() - 1.0F) < 1e-4F,
                        "unloaded night with bed credits 1.0, got " + reloadedBed.sleep().lastNight());
                helper.assertTrue(Math.abs(reloadedRough.sleep().lastNight() - 0.5F) < 1e-4F,
                        "unloaded night without bed credits 0.5, got " + reloadedRough.sleep().lastNight());

                reloadedBed.discard();
                reloadedRough.discard();

                // Loaded awake all night: step 1 tick at a time across night 12 -> 0.0.
                long start = CivilianSleep.nightStart(TEST_NIGHT + 2);
                long end = CivilianSleep.nightEnd(TEST_NIGHT + 2);
                level.setDayTime(start);
                awakeAllNight.sleep().setLastDayTime(start - 1);
                for (long t = start; t <= end; t++) {
                    level.setDayTime(t);
                    awakeAllNight.aiStep();
                }
                helper.assertTrue(Math.abs(awakeAllNight.sleep().lastNight() - 0.0F) < 1e-4F,
                        "loaded awake all night finalises at 0.0, got " + awakeAllNight.sleep().lastNight());

                // Free civilian ignores sleep even at bedtime.
                level.setDayTime(CivilianSleep.ownBedtime(TEST_NIGHT + 2, freeCivilian.sleepNeedTicks()));
                freeCivilian.aiStep();
                helper.assertFalse(freeCivilian.isLyingDown(), "free civilian does not sleep");
                helper.assertTrue(Float.isNaN(freeCivilian.sleep().lastNight()), "free civilian records no sleep");
            } finally {
                level.setDayTime(savedTime);
                withBed.discard();
                noBed.discard();
                awakeAllNight.discard();
                freeCivilian.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    private CivilianSleepGameTests() {
    }
}
