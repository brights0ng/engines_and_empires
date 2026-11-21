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
                Set.of(b1, b2));
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

                // Break roof over autoRoom -> INVALID(EXPOSED_TO_SKY) and immediate detachment.
                BlockPos autoRoof = autoRoom.offset(2, 2, 2);
                level.setBlock(autoRoof, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                long t0 = level.getGameTime();
                RegionManager.validateRegion(level, data, autoRegion, t0);

                helper.assertFalse(autoRegion.valid(), "auto HOME became INVALID");
                helper.assertTrue(autoRegion.invalidReason() == RegionInvalidReason.EXPOSED_TO_SKY,
                        "reason is EXPOSED_TO_SKY: " + autoRegion.invalidReason());
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

    private RegionGameTests() {
    }
}
