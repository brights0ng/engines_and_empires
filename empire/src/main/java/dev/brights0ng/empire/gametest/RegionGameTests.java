// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.region.HomeRegionScanner;
import dev.brights0ng.empire.region.RegionManager;
import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.empire.rules.UnstakeResult;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Game tests for the Empire regions framework and {@link RegionType#HOME} validation, capacity, invalidation,
 * detachment, expiry and persistence.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class RegionGameTests {

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
        CivilianRoster.recruit(civilian, empire, helper.getLevel());
        return civilian;
    }

    private static BlockPos buildRoom(ServerLevel level, BlockPos origin, int innerW, int innerH, int innerD,
                                      Block roofBlock, boolean lit, List<BlockPos> placed) {
        for (int dx = -1; dx <= innerW; dx++) {
            for (int dy = -1; dy <= innerH; dy++) {
                for (int dz = -1; dz <= innerD; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    boolean isRoof = dy == innerH && dx >= 0 && dx < innerW && dz >= 0 && dz < innerD;
                    boolean isShell = dx == -1 || dx == innerW || dy == -1 || dy == innerH || dz == -1 || dz == innerD;
                    BlockState state = isRoof ? roofBlock.defaultBlockState()
                            : (isShell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                    level.setBlock(p, state, Block.UPDATE_ALL);
                    placed.add(p);
                }
            }
        }
        if (lit) {
            BlockPos torch = origin.offset(innerW / 2, 0, innerD / 2);
            level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
            placed.add(torch);
        }
        return origin;
    }

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

    @GameTest(template = GameTestStructures.EMPTY, batch = "regions_sync")
    public static void blockKeysMatchesBlockPosAndSavedDataV5RoundTrips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int[][] samples = {{0, 0, 0}, {123, 64, -456}, {-1, -64, -1}, {-30000, 319, 30000}};
        for (int[] s : samples) {
            long expected = BlockPos.asLong(s[0], s[1], s[2]);
            long actual = BlockKeys.pack(s[0], s[1], s[2]);
            helper.assertTrue(expected == actual,
                    "BlockKeys.pack matches BlockPos.asLong for " + s[0] + "," + s[1] + "," + s[2]);
        }

        // V5 save/load round-trip with region, inhabitants and spatial index.
        EmpireSavedData fresh = new EmpireSavedData();
        UUID founder = UUID.randomUUID();
        EmpireRecord empire = fresh.createEmpire(founder, ChunkKeys.pack(0, 0), new BlockPos(4, 64, 4), 100L);
        long b1 = BlockPos.asLong(1, 64, 1);
        long b2 = BlockPos.asLong(1, 65, 1);
        UUID citizenId = UUID.randomUUID();
        RegionRecord region = new RegionRecord(UUID.randomUUID(), empire.id(), RegionType.HOME, RegionOrigin.MANUAL,
                b2, Set.of(b1, b2));
        region.addInhabitant(citizenId);
        region.markInvalid(RegionInvalidReason.TOO_DARK, 500L);
        fresh.addRegion(region);

        CompoundTag saved = fresh.save(new CompoundTag(), level.registryAccess());
        helper.assertTrue(saved.getInt("version") == EmpireSavedData.SAVE_VERSION, "saved version is 5");

        EmpireSavedData loaded = EmpireSavedData.load(saved, level.registryAccess());
        RegionRecord reloaded = loaded.region(region.id());
        helper.assertTrue(reloaded != null, "region survived save/load");
        helper.assertTrue(reloaded.origin() == RegionOrigin.MANUAL, "origin preserved");
        helper.assertTrue(reloaded.type() == RegionType.HOME, "type preserved");
        helper.assertTrue(reloaded.seed() == b2, "seed preserved");
        helper.assertTrue(reloaded.blockCount() == 2 && reloaded.containsBlock(b1) && reloaded.containsBlock(b2),
                "blocks preserved");
        helper.assertTrue(reloaded.inhabitants().contains(citizenId), "inhabitants preserved");
        helper.assertFalse(reloaded.valid(), "invalid state preserved");
        helper.assertTrue(reloaded.invalidReason() == RegionInvalidReason.TOO_DARK, "invalid reason preserved");
        helper.assertTrue(reloaded.invalidSinceGameTime() == 500L, "invalidSince preserved");
        helper.assertTrue(reloaded.equals(loaded.regionAt(b1)), "spatial index rebuilt on load");

        // Tolerant v4 load (no "regions" tag).
        saved.remove("regions");
        saved.putInt("version", 4);
        EmpireSavedData fromV4 = EmpireSavedData.load(saved, level.registryAccess());
        helper.assertTrue(fromV4.allRegions().isEmpty(), "v4 file loads cleanly with no regions");
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_validation", timeoutTicks = 20)
    public static void homeScanChecksVolumeHeightBedAndLighting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // 1) Too small: 4x2x4 = 32 blocks (lit, has bed).
        BlockPos small = buildRoom(level, main.offset(2, 0, -2), 4, 2, 4, Blocks.STONE, true, placed);
        placeBed(level, small.offset(0, 0, 0), Direction.EAST, placed);

        // 2) Too short: 7x1x8 = 56 blocks, height 1 (lit, has bed).
        BlockPos flat = buildRoom(level, main.offset(-10, 0, -4), 7, 1, 8, Blocks.STONE, true, placed);
        placeBed(level, flat.offset(0, 0, 0), Direction.EAST, placed);

        // 3) No bed: 5x2x5 = 50 blocks, lit, no bed.
        BlockPos noBed = buildRoom(level, main.offset(2, 0, 5), 5, 2, 5, Blocks.STONE, true, placed);

        // 4) Too dark: 5x2x5 = 50 blocks, has bed, unlit.
        BlockPos dark = buildRoom(level, main.offset(-8, 0, 6), 5, 2, 5, Blocks.STONE, false, placed);
        placeBed(level, dark.offset(0, 0, 0), Direction.EAST, placed);

        helper.runAtTickTime(3, () -> {
            try {
                helper.assertTrue(HomeRegionScanner.scan(level, data, empire, small, RegionOrigin.AUTO).failure()
                        == RegionInvalidReason.TOO_SMALL, "32-block room fails TOO_SMALL");
                helper.assertTrue(HomeRegionScanner.scan(level, data, empire, flat, RegionOrigin.AUTO).failure()
                        == RegionInvalidReason.TOO_SHORT, "1-high room fails TOO_SHORT");
                helper.assertTrue(HomeRegionScanner.scan(level, data, empire, noBed, RegionOrigin.AUTO).failure()
                        == RegionInvalidReason.NO_BED, "bedless room fails NO_BED");
                helper.assertTrue(HomeRegionScanner.scan(level, data, empire, dark, RegionOrigin.AUTO).failure()
                        == RegionInvalidReason.TOO_DARK, "unlit room fails TOO_DARK");
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_glass_hazard", timeoutTicks = 20)
    public static void homeAllowsGlassRoofAndRejectsAdjacentOrInteriorHazards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos room = buildRoom(level, main.offset(2, 0, -2), 5, 2, 5, Blocks.GLASS, true, placed);
        placeBed(level, room.offset(0, 0, 0), Direction.EAST, placed);

        helper.runAtTickTime(3, () -> {
            try {
                HomeRegionScanner.ScanResult ok = HomeRegionScanner.scan(level, data, empire, room, RegionOrigin.AUTO);
                helper.assertTrue(ok.succeeded(), "glass-roofed room is valid HOME: " + ok.failure());
                helper.assertTrue(ok.region().blockCount() == 50, "exact 50 interior blocks");

                // Adjacent TNT in wall aborts fill with HAZARD.
                BlockPos wallPos = room.offset(-1, 0, 2);
                level.setBlock(wallPos, Blocks.TNT.defaultBlockState(), Block.UPDATE_ALL);
                HomeRegionScanner.ScanResult tntRes = HomeRegionScanner.scan(level, data, empire, room, RegionOrigin.AUTO);
                helper.assertTrue(tntRes.failure() == RegionInvalidReason.HAZARD, "adjacent TNT fails HAZARD: " + tntRes.failure());

                // Replace TNT with stone, put lava inside -> HAZARD.
                level.setBlock(wallPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(room.offset(4, 0, 4), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
                HomeRegionScanner.ScanResult lavaRes = HomeRegionScanner.scan(level, data, empire, room, RegionOrigin.AUTO);
                helper.assertTrue(lavaRes.failure() == RegionInvalidReason.HAZARD, "interior lava fails HAZARD: " + lavaRes.failure());
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_capacity", timeoutTicks = 20)
    public static void homeCapacityScalesByVolumeAndRejectsOutdoorBed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // Bare outdoor bed near main: must never be claimed (D8).
        level.setBlock(main.offset(0, -1, 2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(main.offset(1, -1, 2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        placed.add(main.offset(0, -1, 2));
        placed.add(main.offset(1, -1, 2));
        placeBed(level, main.offset(0, 0, 2), Direction.EAST, placed);

        // 50-block HOME (capacity 1) with 2 beds (smallBed1 strictly closer to main than bed 2).
        BlockPos smallHome = buildRoom(level, main.offset(3, 0, -2), 5, 2, 5, Blocks.STONE, true, placed);
        BlockPos smallBed1 = placeBed(level, smallHome.offset(0, 0, 0), Direction.EAST, placed);
        placeBed(level, smallHome.offset(3, 0, 4), Direction.EAST, placed);

        // 75-block HOME (capacity 2) with 2 beds.
        BlockPos medHome = buildRoom(level, main.offset(-9, 0, -2), 5, 3, 5, Blocks.STONE, true, placed);
        placeBed(level, medHome.offset(0, 0, 0), Direction.EAST, placed);
        placeBed(level, medHome.offset(0, 0, 4), Direction.EAST, placed);

        CivilianEntity c1 = citizen(helper, empire, main.offset(0, 0, 0));
        CivilianEntity c2 = citizen(helper, empire, main.offset(0, 0, 0));
        CivilianEntity c3 = citizen(helper, empire, main.offset(0, 0, 0));
        CivilianEntity c4 = citizen(helper, empire, main.offset(0, 0, 0));

        helper.runAtTickTime(3, () -> {
            try {
                BlockPos b1 = CivilianBeds.ensureOrClaimBed(level, empire, c1);
                BlockPos b2 = CivilianBeds.ensureOrClaimBed(level, empire, c2);
                BlockPos b3 = CivilianBeds.ensureOrClaimBed(level, empire, c3);
                BlockPos b4 = CivilianBeds.ensureOrClaimBed(level, empire, c4);

                helper.assertTrue(smallBed1.equals(b1), "c1 claims bed in nearest valid HOME (not outdoor bed): " + b1);
                RegionRecord rSmall = data.regionAt(smallHome);
                helper.assertTrue(rSmall != null && rSmall.origin() == RegionOrigin.AUTO && rSmall.inhabitants().size() == 1,
                        "50-block AUTO HOME created with 1 inhabitant");

                // c2 cannot join the 50-block HOME (capacity 1) even though it has a 2nd bed, so c2 claims in medHome.
                helper.assertTrue(b2 != null && !rSmall.containsBlock(b2), "c2 skipped full 50-block HOME");
                RegionRecord rMed = data.regionAt(medHome);
                helper.assertTrue(rMed != null && rMed.containsBlock(b2), "c2 claimed in 75-block HOME");
                helper.assertTrue(b3 != null && rMed.containsBlock(b3), "c3 also fits in 75-block HOME (cap 2)");
                helper.assertTrue(rMed.inhabitants().size() == 2, "medHome has 2 inhabitants");
                helper.assertTrue(b4 == null, "c4 gets no bed once both HOMEs are at capacity (outdoor bed ignored)");
            } finally {
                c1.discard();
                c2.discard();
                c3.discard();
                c4.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_lifecycle", timeoutTicks = 20)
    public static void invalidationDetachesInhabitantsRecoversAndExpiresAutoOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos autoRoom = buildRoom(level, main.offset(2, 0, -2), 5, 2, 5, Blocks.STONE, true, placed);
        BlockPos autoBed = placeBed(level, autoRoom.offset(0, 0, 0), Direction.EAST, placed);

        BlockPos manualRoom = buildRoom(level, main.offset(-8, 0, -2), 5, 2, 5, Blocks.STONE, true, placed);
        placeBed(level, manualRoom.offset(0, 0, 0), Direction.EAST, placed);

        CivilianEntity c1 = citizen(helper, empire, main.offset(0, 0, 0));

        helper.runAtTickTime(3, () -> {
            try {
                BlockPos claimed = CivilianBeds.ensureOrClaimBed(level, empire, c1);
                helper.assertTrue(autoBed.equals(claimed), "citizen claimed auto HOME bed");
                RegionRecord autoRegion = data.regionAt(autoRoom);
                helper.assertTrue(autoRegion != null && autoRegion.valid() && autoRegion.inhabitants().contains(c1.civilianId()),
                        "auto HOME valid with inhabitant");

                HomeRegionScanner.ScanResult manualScan = HomeRegionScanner.scan(level, data, empire, manualRoom, RegionOrigin.MANUAL);
                helper.assertTrue(manualScan.succeeded(), "manual HOME scanned");
                RegionRecord manualRegion = manualScan.region();
                data.addRegion(manualRegion);

                // Break roof over 50-block autoRoom -> sky-exposed column becomes BOUNDARY, shrinking below 50 (TOO_SMALL).
                BlockPos autoRoof = autoRoom.offset(2, 2, 2);
                level.setBlock(autoRoof, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                long t0 = level.getGameTime();
                RegionManager.validateRegion(level, data, autoRegion, t0);

                helper.assertFalse(autoRegion.valid(), "auto HOME became INVALID");
                helper.assertTrue(autoRegion.invalidReason() == RegionInvalidReason.TOO_SMALL,
                        "reason is TOO_SMALL: " + autoRegion.invalidReason());
                helper.assertTrue(autoRegion.inhabitants().isEmpty(), "inhabitants detached on invalidation");
                helper.assertTrue(c1.claimedBed() == null, "loaded body bed cleared");
                CivilianRecord rec = data.civilian(c1.civilianId());
                helper.assertTrue(rec != null && rec.bed() == null, "master record bed cleared");
                helper.assertTrue(level.getPoiManager().getFreeTickets(autoBed) == 1, "bed POI released");

                // Repair roof -> recovers back to VALID.
                level.setBlock(autoRoof, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                RegionManager.validateRegion(level, data, autoRegion, t0 + 100L);
                helper.assertTrue(autoRegion.valid() && autoRegion.invalidReason() == RegionInvalidReason.NONE,
                        "repaired auto HOME recovered to VALID");

                // Now invalidate both AUTO and MANUAL and test 3-day auto-deletion.
                level.setBlock(autoRoof, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(manualRoom.offset(2, 2, 2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                long t1 = t0 + 200L;
                RegionManager.validateRegion(level, data, autoRegion, t1);
                RegionManager.validateRegion(level, data, manualRegion, t1);

                RegionManager.validateRegion(level, data, autoRegion, t1 + RegionRules.INVALID_AUTO_DELETE_TICKS - 1L);
                helper.assertTrue(data.region(autoRegion.id()) != null, "AUTO region kept 1 tick before expiry");

                RegionManager.validateRegion(level, data, autoRegion, t1 + RegionRules.INVALID_AUTO_DELETE_TICKS);
                RegionManager.validateRegion(level, data, manualRegion, t1 + RegionRules.INVALID_AUTO_DELETE_TICKS);
                helper.assertTrue(data.region(autoRegion.id()) == null, "AUTO region auto-deleted at 3 MC days");
                helper.assertTrue(data.region(manualRegion.id()) != null, "MANUAL region never auto-deleted");
            } finally {
                c1.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_unstake", timeoutTicks = 20)
    public static void unstakingChunkInvalidatesTouchingHome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // Stake an extra chunk 2 east of main's chunk (outside the protected 3x3 so it can be unstaked).
        int extraCx = (main.getX() >> 4) + 2;
        int extraCz = main.getZ() >> 4;
        long extraChunk = ChunkKeys.pack(extraCx, extraCz);
        StakeResult staked = data.stakeChunk(empire, extraChunk, 0L, true);
        helper.assertTrue(staked == StakeResult.OK, "staked extra chunk: " + staked);

        BlockPos roomOrigin = new BlockPos(extraCx * 16 + 4, main.getY(), extraCz * 16 + 4);
        buildRoom(level, roomOrigin, 5, 2, 5, Blocks.STONE, true, placed);
        placeBed(level, roomOrigin.offset(0, 0, 0), Direction.EAST, placed);

        helper.runAtTickTime(3, () -> {
            try {
                HomeRegionScanner.ScanResult scan = HomeRegionScanner.scan(level, data, empire, roomOrigin, RegionOrigin.MANUAL);
                helper.assertTrue(scan.succeeded(), "HOME in extra chunk scanned: " + scan.failure());
                RegionRecord region = scan.region();
                data.addRegion(region);

                UnstakeResult unstaked = data.unstakeChunk(empire, extraChunk, 0L);
                helper.assertTrue(unstaked == UnstakeResult.OK, "unstaked extra chunk: " + unstaked);
                helper.assertTrue(region.isDirtyFlag(), "unstaking marked touching region dirty");

                RegionManager.validateRegion(level, data, region, level.getGameTime());
                helper.assertFalse(region.valid(), "region invalidated after unstake");
                helper.assertTrue(region.invalidReason() == RegionInvalidReason.UNSTAKED,
                        "reason is UNSTAKED: " + region.invalidReason());
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_whitelist", timeoutTicks = 20)
    public static void homeWhitelistIncludesNonCollidingAndClimbableBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // 5x2x5 = 50 interior blocks, plus 2 solid/fluid boundary blocks inside (so build 6x2x5 = 60 -> 58 interior).
        BlockPos room = buildRoom(level, main.offset(2, 0, -2), 6, 2, 5, Blocks.STONE, true, placed);
        placeBed(level, room.offset(0, 0, 0), Direction.EAST, placed);

        BlockPos signPos = room.offset(1, 0, 2);
        BlockPos buttonPos = room.offset(2, 0, 0);
        BlockPos platePos = room.offset(2, 0, 2);
        BlockPos railPos = room.offset(4, 0, 2);
        BlockPos ladderPos = room.offset(0, 0, 4);
        BlockPos scaffoldingPos = room.offset(1, 0, 4);
        BlockPos tablePos = room.offset(5, 0, 0);
        BlockPos waterPos = room.offset(5, 0, 4);

        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState(), flags);
        level.setBlock(buttonPos, Blocks.STONE_BUTTON.defaultBlockState(), flags);
        level.setBlock(platePos, Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), flags);
        level.setBlock(railPos, Blocks.RAIL.defaultBlockState(), flags);
        level.setBlock(ladderPos, Blocks.LADDER.defaultBlockState(), flags);
        level.setBlock(scaffoldingPos, Blocks.SCAFFOLDING.defaultBlockState(), flags);
        level.setBlock(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState(), flags);
        level.setBlock(waterPos, Blocks.WATER.defaultBlockState(), flags);

        helper.runAtTickTime(3, () -> {
            try {
                HomeRegionScanner.ScanResult scan = HomeRegionScanner.scan(level, data, empire, room, RegionOrigin.AUTO);
                helper.assertTrue(scan.succeeded(), "room with non-colliding and climbable blocks scans: " + scan.failure());
                RegionRecord region = scan.region();
                helper.assertTrue(region.containsBlock(signPos), "sign (no collision) included in HOME");
                helper.assertTrue(region.containsBlock(buttonPos), "button (no collision) included in HOME");
                helper.assertTrue(region.containsBlock(platePos), "pressure plate (no collision) included in HOME");
                helper.assertTrue(region.containsBlock(railPos), "rail (no collision) included in HOME");
                helper.assertTrue(region.containsBlock(ladderPos), "ladder (climbable) included in HOME");
                helper.assertTrue(region.containsBlock(scaffoldingPos), "scaffolding (climbable) included in HOME");
                helper.assertFalse(region.containsBlock(tablePos), "crafting table (solid) excluded as boundary");
                helper.assertFalse(region.containsBlock(waterPos), "water (fluid) excluded as boundary");
                helper.assertTrue(region.blockCount() == 58, "expected 58 interior blocks, got " + region.blockCount());
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_reshape", timeoutTicks = 30)
    public static void autoHomeReshapesAndReconcilesInhabitantsWhileManualStaysFixed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // 8x2x5 = 80 interior blocks (capacity 2), two beds on opposite ends (x=0..1 and x=6..7).
        BlockPos autoRoom = buildRoom(level, main.offset(2, 0, -2), 8, 2, 5, Blocks.STONE, true, placed);
        BlockPos seedBed = placeBed(level, autoRoom.offset(0, 0, 2), Direction.EAST, placed); // head at (1,0,2)
        BlockPos farBed = placeBed(level, autoRoom.offset(6, 0, 2), Direction.EAST, placed);  // head at (7,0,2)

        // Manual 5x2x5 = 50 blocks (placed further from main than farBed so c2 claims farBed in autoRoom first).
        BlockPos manualRoom = buildRoom(level, main.offset(-14, 0, -2), 5, 2, 5, Blocks.STONE, true, placed);
        placeBed(level, manualRoom.offset(0, 0, 0), Direction.EAST, placed);

        CivilianEntity c1 = citizen(helper, empire, main.offset(0, 0, 0));
        CivilianEntity c2 = citizen(helper, empire, main.offset(0, 0, 0));

        helper.runAtTickTime(3, () -> {
            try {
                BlockPos b1 = CivilianBeds.ensureOrClaimBed(level, empire, c1);
                BlockPos b2 = CivilianBeds.ensureOrClaimBed(level, empire, c2);
                helper.assertTrue(seedBed.equals(b1), "c1 claimed seed bed: " + b1);
                helper.assertTrue(farBed.equals(b2), "c2 claimed far bed: " + b2);

                RegionRecord auto = data.regionAt(seedBed);
                helper.assertTrue(auto != null && auto.blockCount() == 80 && auto.seed() == seedBed.asLong(),
                        "AUTO HOME starts at 80 blocks seeded at seedBed");
                helper.assertTrue(auto.inhabitants().size() == 2, "both citizens inhabit AUTO HOME");

                HomeRegionScanner.ScanResult manualScan = HomeRegionScanner.scan(level, data, empire, manualRoom, RegionOrigin.MANUAL);
                helper.assertTrue(manualScan.succeeded(), "MANUAL HOME scanned");
                RegionRecord manual = manualScan.region();
                data.addRegion(manual);

                // 1) Build an interior wall at x=5 cutting off farBed (x=6..7), leaving 5x2x5 = 50 blocks (cap 1).
                for (int dy = 0; dy < 2; dy++) {
                    for (int dz = 0; dz < 5; dz++) {
                        level.setBlock(autoRoom.offset(5, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                // Place a second torch in the kept half so lighting is guaranteed even after the wall splits the room.
                level.setBlock(autoRoom.offset(2, 0, 2), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);

                RegionManager.updateRegion(level, data, auto, level.getGameTime());
                helper.assertTrue(auto.valid(), "cut AUTO HOME stays VALID: " + auto.invalidReason());
                helper.assertTrue(auto.blockCount() == 50, "AUTO HOME shrank to 50 blocks, got " + auto.blockCount());
                helper.assertFalse(auto.containsBlock(farBed), "cut-off bed removed from AUTO HOME blocks");
                helper.assertTrue(data.regionAt(farBed) == null, "spatial index cleared for cut-off blocks");
                helper.assertTrue(auto.inhabitants().contains(c1.civilianId()), "c1 kept in AUTO HOME");
                helper.assertFalse(auto.inhabitants().contains(c2.civilianId()), "cut-off c2 detached from AUTO HOME");
                helper.assertTrue(c2.claimedBed() == null, "cut-off c2 bed cleared");
                helper.assertTrue(level.getPoiManager().getFreeTickets(farBed) == 1, "cut-off bed POI released");

                // 2) Knock down the interior wall -> AUTO HOME expands back to 80 blocks.
                for (int dy = 0; dy < 2; dy++) {
                    for (int dz = 0; dz < 5; dz++) {
                        level.setBlock(autoRoom.offset(5, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                RegionManager.updateRegion(level, data, auto, level.getGameTime() + 25L);
                helper.assertTrue(auto.valid() && auto.blockCount() == 80,
                        "reopened AUTO HOME expanded back to 80 blocks, got " + auto.blockCount());
                helper.assertTrue(auto.equals(data.regionAt(farBed)), "spatial index updated on expansion");

                // 3) Place solid block inside MANUAL HOME -> MANUAL never reshapes (stays 50 blocks).
                BlockPos manualInner = manualRoom.offset(4, 0, 4);
                level.setBlock(manualInner, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                RegionManager.updateRegion(level, data, manual, level.getGameTime() + 25L);
                helper.assertTrue(manual.valid() && manual.blockCount() == 50 && manual.containsBlock(manualInner),
                        "MANUAL HOME never reshapes on interior block placement");
            } finally {
                c1.discard();
                c2.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_seed_migration", timeoutTicks = 20)
    public static void autoHomeSeedMigratesToOtherBedOrStaysFixed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // 6x2x5 = 60 blocks with two beds.
        BlockPos room = buildRoom(level, main.offset(2, 0, -2), 6, 2, 5, Blocks.STONE, true, placed);
        BlockPos bed1Foot = room.offset(0, 0, 0);
        BlockPos bed1Head = placeBed(level, bed1Foot, Direction.EAST, placed);
        BlockPos bed2Foot = room.offset(0, 0, 4);
        BlockPos bed2Head = placeBed(level, bed2Foot, Direction.EAST, placed);

        helper.runAtTickTime(3, () -> {
            try {
                HomeRegionScanner.ScanResult scan = HomeRegionScanner.scan(level, data, empire, bed1Head, RegionOrigin.AUTO);
                helper.assertTrue(scan.succeeded(), "AUTO HOME scanned: " + scan.failure());
                RegionRecord region = scan.region();
                data.addRegion(region);
                helper.assertTrue(region.seed() == bed1Head.asLong(), "initial seed is bed1Head");

                // Break bed1 and place stone on bed1Head -> seed migrates to bed2Head and region stays valid (59 blocks).
                int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
                level.setBlock(bed1Foot, Blocks.AIR.defaultBlockState(), flags);
                level.setBlock(bed1Head, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                RegionManager.updateRegion(level, data, region, level.getGameTime());

                helper.assertTrue(region.valid(), "HOME stays valid after seed migrates to bed2Head: " + region.invalidReason());
                helper.assertTrue(region.seed() == bed2Head.asLong(), "seed migrated to bed2Head");
                helper.assertTrue(region.blockCount() == 59, "stone on old seed excluded from shape, got " + region.blockCount());

                // Now remove bed2 (last bed) and cover bed2Head with stone -> no other bed, seed stays fixed -> INVALID.
                level.setBlock(bed2Foot, Blocks.AIR.defaultBlockState(), flags);
                level.setBlock(bed2Head, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                RegionManager.updateRegion(level, data, region, level.getGameTime() + 25L);

                helper.assertFalse(region.valid(), "covering sole remaining seed bed invalidates HOME");
                helper.assertTrue(region.seed() == bed2Head.asLong(), "seed stays fixed when no other bed exists");
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_throttle", timeoutTicks = 40)
    public static void chunkUpdateTriggersLeadingAndTrailingHomeUpdatesWithCooldown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // 6x2x5 = 60 blocks.
        BlockPos room = buildRoom(level, main.offset(2, 0, -2), 6, 2, 5, Blocks.STONE, true, placed);
        BlockPos bedHead = placeBed(level, room.offset(0, 0, 0), Direction.EAST, placed);
        RegionRecord[] holder = new RegionRecord[1];

        helper.runAtTickTime(3, () -> {
            HomeRegionScanner.ScanResult scan = HomeRegionScanner.scan(level, data, empire, bedHead, RegionOrigin.AUTO);
            helper.assertTrue(scan.succeeded(), "initial AUTO HOME scanned: " + scan.failure());
            holder[0] = scan.region();
            data.addRegion(holder[0]);
            // Place 1st stone block inside -> LevelChunkRegionMixin prods region -> leading update runs at tick 4.
            level.setBlock(room.offset(5, 0, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(RegionManager.hasScheduledUpdate(holder[0].id()), "chunk update scheduled leading update");
        });

        helper.runAtTickTime(5, () -> {
            helper.assertTrue(holder[0].blockCount() == 59,
                    "leading update shrank HOME to 59 blocks, got " + holder[0].blockCount());
            // Place 2nd stone block during the 20-tick cooldown -> must NOT update immediately, but queues trailing update.
            level.setBlock(room.offset(5, 0, 4), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(RegionManager.hasScheduledUpdate(holder[0].id()), "prod during cooldown queued trailing update");
        });

        helper.runAtTickTime(10, () -> {
            helper.assertTrue(holder[0].blockCount() == 59,
                    "still in 1-second cooldown at tick 10 (59 blocks), got " + holder[0].blockCount());
        });

        helper.runAtTickTime(26, () -> {
            try {
                helper.assertTrue(holder[0].blockCount() == 58,
                        "trailing update after 1-second cooldown shrank HOME to 58 blocks, got " + holder[0].blockCount());
                helper.assertFalse(RegionManager.hasScheduledUpdate(holder[0].id()), "no further update queued once settled");
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_exterior_door", timeoutTicks = 20)
    public static void homeWithExteriorDoorUnderSkyStaysValid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // 5x2x5 room with an oak door in the east exterior wall (facing away from the GameTest structure into open sky).
        BlockPos room = buildRoom(level, main.offset(2, 0, -2), 5, 2, 5, Blocks.STONE, true, placed);
        BlockPos bedHead = placeBed(level, room.offset(0, 0, 0), Direction.EAST, placed);
        BlockPos doorLower = room.offset(5, 0, 2);
        BlockPos doorUpper = room.offset(5, 1, 2);
        BlockPos doorLintel = room.offset(5, 2, 2);
        int flags = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(doorLower, Blocks.OAK_DOOR.defaultBlockState(), flags);
        level.setBlock(doorUpper, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), flags);
        // Remove the lintel so the door itself is directly exposed to open sky.
        level.setBlock(doorLintel, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        helper.runAtTickTime(5, () -> {
            try {
                // Case A: exterior door directly under open sky -> doorUpper is sky-exposed (BOUNDARY), doorLower is
                // sheltered by motion-blocking doorUpper -> 51 blocks, VALID (not invalidated by EXPOSED_TO_SKY).
                HomeRegionScanner.ScanResult underSky = HomeRegionScanner.scan(level, data, empire, bedHead, RegionOrigin.AUTO);
                helper.assertTrue(underSky.succeeded(),
                        "HOME with exterior door under open sky is valid: " + underSky.failure());
                helper.assertTrue(underSky.region().blockCount() == 51,
                        "sky-exposed upper door treated as boundary (51 blocks), got " + underSky.region().blockCount());
                helper.assertFalse(underSky.region().containsBlock(doorUpper), "sky-exposed upper door excluded");

                // Case B: exterior door under a lintel -> door included (52 blocks), open sky outside treated as BOUNDARY.
                level.setBlock(doorLintel, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                HomeRegionScanner.ScanResult underLintel = HomeRegionScanner.scan(level, data, empire, bedHead, RegionOrigin.AUTO);
                helper.assertTrue(underLintel.succeeded(), "HOME with exterior door under lintel is valid: " + underLintel.failure());
                helper.assertTrue(underLintel.region().blockCount() == 52,
                        "linteled exterior door included while outside sky is boundary (52 blocks), got " + underLintel.region().blockCount());
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "region_bed_priority", timeoutTicks = 20)
    public static void claimPrefersExistingHomeAndMarksFailedBedsUntilChunkUpdateOrDawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);

        // 1) Closer homeless bed (invalid: outdoor) at main + (0,0,2).
        level.setBlock(main.offset(0, -1, 2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(main.offset(1, -1, 2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        placed.add(main.offset(0, -1, 2));
        placed.add(main.offset(1, -1, 2));
        BlockPos outdoorBed = placeBed(level, main.offset(0, 0, 2), Direction.EAST, placed);

        // 2) Closer valid homeless room at main + (3,0,-2) (distance ~3).
        BlockPos closerRoom = buildRoom(level, main.offset(3, 0, -2), 5, 2, 5, Blocks.STONE, true, placed);
        BlockPos closerBed = placeBed(level, closerRoom.offset(0, 0, 2), Direction.EAST, placed);

        // 3) Further existing HOME (capacity 2) at main + (-12,0,-2) (distance ~11) with 2 beds.
        BlockPos farRoom = buildRoom(level, main.offset(-12, 0, -2), 5, 3, 5, Blocks.STONE, true, placed);
        BlockPos farBed1 = placeBed(level, farRoom.offset(0, 0, 0), Direction.EAST, placed);
        BlockPos farBed2 = placeBed(level, farRoom.offset(0, 0, 4), Direction.EAST, placed);

        CivilianEntity c1 = citizen(helper, empire, main.offset(0, 0, 0));
        CivilianEntity c2 = citizen(helper, empire, main.offset(0, 0, 0));

        helper.runAtTickTime(3, () -> {
            try {
                // Pre-create farRoom as an existing HOME so it has 2 free beds.
                HomeRegionScanner.ScanResult pre = HomeRegionScanner.scan(level, data, empire, farBed1, RegionOrigin.AUTO);
                helper.assertTrue(pre.succeeded(), "farRoom pre-scanned");
                data.addRegion(pre.region());

                // c1 must prefer the existing HOME (farRoom) over the closer homeless room (closerRoom) & outdoor bed!
                BlockPos claimed1 = CivilianBeds.ensureOrClaimBed(level, empire, c1);
                helper.assertTrue(farBed1.equals(claimed1) || farBed2.equals(claimed1),
                        "c1 preferred existing HOME over closer homeless bed: " + claimed1);
                helper.assertFalse(RegionManager.isFailedBed(level, outdoorBed),
                        "outdoor bed not even scanned while existing HOME had space");

                // Fill farRoom's 2nd slot with c2, then test a 3rd citizen failing outdoorBed and marking it dirty.
                BlockPos claimed2 = CivilianBeds.ensureOrClaimBed(level, empire, c2);
                helper.assertTrue(claimed2 != null && pre.region().containsBlock(claimed2), "c2 took 2nd slot in farRoom");

                // Break closerRoom's bed so only outdoorBed is left as a homeless candidate.
                int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
                level.setBlock(closerBed, Blocks.AIR.defaultBlockState(), flags);
                level.setBlock(closerRoom.offset(0, 0, 2), Blocks.AIR.defaultBlockState(), flags);

                CivilianEntity c3 = citizen(helper, empire, main.offset(0, 0, 0));
                try {
                    BlockPos claimed3 = CivilianBeds.ensureOrClaimBed(level, empire, c3);
                    helper.assertTrue(claimed3 == null, "c3 found no valid bed");
                    helper.assertTrue(RegionManager.isFailedBed(level, outdoorBed),
                            "failed outdoor bed marked dirty so nobody else retries it");

                    // Chunk update clears the failed-bed mark.
                    level.setBlock(main.offset(0, 1, 2), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
                    placed.add(main.offset(0, 1, 2));
                    helper.assertFalse(RegionManager.isFailedBed(level, outdoorBed),
                            "chunk update cleared failed-bed mark");

                    // Mark again, then advance to dawn -> dawn clears the failed-bed mark.
                    RegionManager.markFailedBed(level, outdoorBed);
                    helper.assertTrue(RegionManager.isFailedBed(level, outdoorBed), "re-marked failed bed");
                    level.setDayTime(savedTime + 24_000L);
                    helper.assertFalse(RegionManager.isFailedBed(level, outdoorBed), "dawn cleared failed-bed mark");
                } finally {
                    c3.discard();
                }
            } finally {
                level.setDayTime(savedTime);
                c1.discard();
                c2.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    private RegionGameTests() {
    }
}
