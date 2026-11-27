// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianOccupations;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.civilian.ai.AiContext;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.ledger.LedgerSelection;
import dev.brights0ng.empire.region.FarmRegionScanner;
import dev.brights0ng.empire.region.RegionManager;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.Occupation;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.ScheduleClock;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Integration tests for the Occupation system and {@link Occupation#FARMER}: Administrative Ledger assignment and
 * shift-click unassignment, cap of 1 farmer per farm, walking to and standing near the seed shed during outdoor work
 * hours, pause-without-unassigning on farm invalidation, cleanup on deletion/death, and NBT persistence.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class FarmOccupationGameTests {

    private static final long TEST_SCHEDULE_DAY = 25L;

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

    private static CivilianEntity spawnCitizen(GameTestHelper helper, EmpireRecord empire, BlockPos pos) {
        CivilianEntity c = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(pos), null);
        c.setTraits(CivilianTraits.NEUTRAL);
        CivilianRoster.recruit(c, empire, helper.getLevel());
        return c;
    }

    private static void setBlock(ServerLevel level, BlockPos pos, BlockState state, List<BlockPos> placed) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        placed.add(pos);
    }

    private static void buildFloor(ServerLevel level, BlockPos center, int radius, List<BlockPos> placed) {
        int y = center.getY() - 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                setBlock(level, floor, Blocks.STONE.defaultBlockState(), placed);
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

    private static PlayerInteractEvent.RightClickBlock rightClickBlock(Player player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
    }

    private static InteractionResult rightClickEntity(Player player, CivilianEntity civilian) {
        return CommonHooks.onInteractEntity(player, civilian, InteractionHand.MAIN_HAND);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "occupation_ledger", timeoutTicks = 30)
    public static void ledgerAssignsFarmerEnforcesCapAndShiftUnemploysOnlyWhenWorkplaceSelected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        BlockPos compA = main.offset(3, 0, 0);
        BlockPos compB = main.offset(-3, 0, 0);
        setBlock(level, compA, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, compB, Blocks.COMPOSTER.defaultBlockState(), placed);

        CivilianEntity c1 = spawnCitizen(helper, empire, main.offset(0, 0, 2));
        CivilianEntity c2 = spawnCitizen(helper, empire, main.offset(1, 0, 2));
        CivilianEntity freeCiv = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main.offset(-1, 0, 2)), null);

        helper.runAtTickTime(3, () -> {
            try {
                // Create Farm A and Farm B; Farm B ends up selected first, then select Farm A.
                helper.assertTrue(rightClickBlock(founder, compA).isCanceled(), "create Farm A");
                RegionRecord farmA = data.regionAt(compA);
                helper.assertTrue(farmA != null && farmA.type() == RegionType.FARM, "Farm A exists");

                LedgerSelection.clear(level, founder);
                helper.assertTrue(rightClickBlock(founder, compB).isCanceled(), "create Farm B");
                RegionRecord farmB = data.regionAt(compB);
                helper.assertTrue(farmB != null && farmB.type() == RegionType.FARM, "Farm B exists");

                // Switch selection to Farm A.
                helper.assertTrue(rightClickBlock(founder, compA).isCanceled(), "select Farm A");
                helper.assertTrue(farmA.id().equals(LedgerSelection.selectedRegionId(founder.getUUID())), "Farm A selected");

                // Free civilian cannot be assigned.
                helper.assertTrue(rightClickEntity(founder, freeCiv) != null, "ledger click on free civilian intercepted");
                helper.assertTrue(freeCiv.occupation() == Occupation.UNEMPLOYED, "free civilian stays unemployed");
                helper.assertTrue(farmA.workers().isEmpty(), "Farm A still has 0 workers");

                // Assign c1 to Farm A.
                helper.assertTrue(rightClickEntity(founder, c1) == InteractionResult.SUCCESS, "assign c1 to Farm A");
                helper.assertTrue(c1.occupation() == Occupation.FARMER, "c1 is now FARMER");
                helper.assertTrue(farmA.id().equals(CivilianOccupations.workplaceIdOf(c1)), "c1 works at Farm A");
                helper.assertTrue(farmA.hasWorker(c1.civilianId()), "Farm A tracks c1 as worker");

                // Cap of 1: c2 cannot be assigned to Farm A while c1 works there.
                helper.assertTrue(rightClickEntity(founder, c2) == InteractionResult.SUCCESS, "click c2 intercepted");
                helper.assertTrue(c2.occupation() == Occupation.UNEMPLOYED, "c2 rejected by cap of 1");
                helper.assertTrue(farmA.workers().size() == 1, "Farm A still has 1 worker");

                // Shift-click c1 with Farm B selected -> does NOT unemploy (wrong region selected).
                helper.assertTrue(rightClickBlock(founder, compB).isCanceled(), "select Farm B");
                founder.setShiftKeyDown(true);
                helper.assertTrue(rightClickEntity(founder, c1) == InteractionResult.SUCCESS, "shift-click intercepted");
                helper.assertTrue(c1.occupation() == Occupation.FARMER
                        && farmA.id().equals(CivilianOccupations.workplaceIdOf(c1)),
                        "shift-click with wrong region selected did not unemploy c1");

                // Shift-click c1 with NO region selected -> intercepted (no info dump) and does NOT unemploy.
                LedgerSelection.clear(level, founder);
                helper.assertTrue(rightClickEntity(founder, c1) == InteractionResult.SUCCESS,
                        "shift-click while holding ledger suppressed debug info dump");
                helper.assertTrue(c1.occupation() == Occupation.FARMER, "shift-click without selection did not unemploy c1");

                // Re-select Farm A and shift-click c1 -> unemploys c1 cleanly!
                founder.setShiftKeyDown(false);
                helper.assertTrue(rightClickBlock(founder, compA).isCanceled(), "re-select Farm A");
                founder.setShiftKeyDown(true);
                helper.assertTrue(rightClickEntity(founder, c1) == InteractionResult.SUCCESS, "shift-click unemploy c1");
                helper.assertTrue(c1.occupation() == Occupation.UNEMPLOYED, "c1 is now UNEMPLOYED");
                helper.assertTrue(farmA.workers().isEmpty(), "Farm A worker removed");

                // Reassign c1 from Farm A to Farm B directly via normal right-click.
                founder.setShiftKeyDown(false);
                rightClickEntity(founder, c1);
                helper.assertTrue(farmA.hasWorker(c1.civilianId()), "c1 re-hired at Farm A");
                rightClickBlock(founder, compB);
                rightClickEntity(founder, c1);
                helper.assertTrue(farmA.workers().isEmpty(), "Farm A released c1 on reassignment");
                helper.assertTrue(farmB.hasWorker(c1.civilianId())
                        && farmB.id().equals(CivilianOccupations.workplaceIdOf(c1)),
                        "c1 reassigned to Farm B");
            } finally {
                founder.setShiftKeyDown(false);
                LedgerSelection.clear(level, founder);
                c1.discard();
                c2.discard();
                freeCiv.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "occupation_farmer_ai", timeoutTicks = 240)
    public static void farmerWalksToSeedShedAndStandsNearItDuringOutdoorWorkHours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 9, placed);

        // Seed shed (composter + chest) at main + (-5, 0, 0); second shed further east at main + (6, 0, 0).
        BlockPos seedComposter = main.offset(-5, 0, 0);
        BlockPos seedChest = seedComposter.north();
        BlockPos secondBarrel = main.offset(6, 0, 0);
        setBlock(level, seedComposter, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, seedChest, Blocks.CHEST.defaultBlockState(), placed);
        setBlock(level, secondBarrel, Blocks.BARREL.defaultBlockState(), placed);

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity farmer = spawnCitizen(helper, empire, main.offset(2, 0, 0));

        // Create farm at seedComposter and add the second shed so we prove the farmer targets the SEED shed!
        rightClickBlock(founder, seedComposter);
        RegionRecord farm = data.regionAt(seedComposter);
        helper.assertTrue(farm != null && farm.valid(), "farm created");
        rightClickBlock(founder, secondBarrel);
        helper.assertTrue(farm.rooms().size() == 2, "farm has 2 sheds");

        CivilianOccupations.AssignResult res = CivilianOccupations.assign(level, data, farmer, farm);
        helper.assertTrue(res.succeeded(), "assigned farmer");

        AiContext ctx = AiContext.of(level, farmer, data, empire);
        BlockPos resolvedWork = farmer.aiController().locations().resolve(ctx, LocationKey.WORK);
        helper.assertTrue(resolvedWork != null, "seed shed stand spot resolved");
        helper.assertTrue(resolvedWork.distSqr(seedComposter) <= 9.0,
                "resolved work spot is adjacent to seed shed, got " + resolvedWork + " vs seed " + seedComposter);
        helper.assertTrue(resolvedWork.distSqr(seedComposter) < resolvedWork.distSqr(secondBarrel),
                "resolved work spot targets seed shed rather than second shed");

        helper.runAtTickTime(210, () -> {
            try {
                helper.assertTrue(farmer.schedule().workDoneTicks() >= 40,
                        "farmer walked to seed shed and credited work ticks, got " + farmer.schedule().workDoneTicks());
                helper.assertTrue(farmer.blockPosition().distSqr(seedComposter) <= 49.0,
                        "farmer is idling within the 5-6 block shed area: pos=" + farmer.blockPosition()
                                + ", seed=" + seedComposter);
            } finally {
                LedgerSelection.clear(level, founder);
                level.setDayTime(savedTime);
                farmer.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "occupation_invalid_pause", timeoutTicks = 40)
    public static void invalidFarmPausesFarmerWithoutUnassigningAndResumesOnRepair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        BlockPos composter = main.offset(3, 0, 0);
        setBlock(level, composter, Blocks.COMPOSTER.defaultBlockState(), placed);

        CivilianEntity farmer = spawnCitizen(helper, empire, main);
        rightClickBlock(founder, composter);
        RegionRecord farm = data.regionAt(composter);
        helper.assertTrue(farm != null && farm.valid(), "farm created");
        helper.assertTrue(CivilianOccupations.assign(level, data, farmer, farm).succeeded(), "farmer assigned");

        helper.runAtTickTime(4, () -> {
            try {
                AiContext ctx = AiContext.of(level, farmer, data, empire);
                helper.assertTrue(farmer.aiController().locations().isAvailable(ctx, LocationKey.WORK),
                        "WORK available while farm is valid");

                // Break composter -> farm becomes INVALID(NO_SHED); farmer stays assigned but work pauses!
                level.setBlock(composter, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                RegionManager.updateRegion(level, data, farm, level.getGameTime());
                helper.assertFalse(farm.valid(), "farm invalidated");
                helper.assertTrue(farmer.occupation() == Occupation.FARMER,
                        "farmer stayed assigned when farm invalidated");
                helper.assertTrue(farm.hasWorker(farmer.civilianId()), "farm kept worker UUID while invalid");
                helper.assertFalse(farmer.aiController().locations().isAvailable(ctx, LocationKey.WORK),
                        "WORK unavailable (paused) while farm is invalid");

                // Restore composter -> farm becomes VALID again and WORK resumes!
                level.setBlock(composter, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
                RegionManager.updateRegion(level, data, farm, level.getGameTime() + 25L);
                helper.assertTrue(farm.valid(), "farm restored to valid");
                helper.assertTrue(farmer.occupation() == Occupation.FARMER, "farmer still assigned after repair");
                helper.assertTrue(farmer.aiController().locations().isAvailable(ctx, LocationKey.WORK),
                        "WORK available again after farm repair");
            } finally {
                LedgerSelection.clear(level, founder);
                farmer.discard();
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "occupation_lifecycle_nbt", timeoutTicks = 30)
    public static void deletionAndDeathDetachWorkersAndOccupationPersistsThroughNbtAndRespawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 6, placed);
        BlockPos compA = main.offset(3, 0, 0);
        BlockPos compB = main.offset(-3, 0, 0);
        setBlock(level, compA, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, compB, Blocks.COMPOSTER.defaultBlockState(), placed);

        CivilianEntity c1 = spawnCitizen(helper, empire, main);
        CivilianEntity c2 = spawnCitizen(helper, empire, main.offset(0, 0, 1));
        CivilianEntity replacement = null;

        try {
            rightClickBlock(founder, compA);
            RegionRecord farmA = data.regionAt(compA);
            LedgerSelection.clear(level, founder);
            rightClickBlock(founder, compB);
            RegionRecord farmB = data.regionAt(compB);
            helper.assertTrue(farmA != null && farmB != null, "both farms created");

            helper.assertTrue(CivilianOccupations.assign(level, data, c1, farmA).succeeded(), "c1 assigned to Farm A");
            c1.syncNeedsToRecord();

            // NBT round-trip on RegionRecord and CivilianRecord + respawnBody.
            RegionRecord loadedFarm = RegionRecord.load(farmA.save());
            helper.assertTrue(loadedFarm != null && loadedFarm.hasWorker(c1.civilianId()),
                    "RegionRecord NBT preserves workers");

            CivilianRecord rec1 = data.civilian(c1.civilianId());
            helper.assertTrue(rec1 != null, "c1 record exists");
            CompoundTag recTag = rec1.save(level.registryAccess());
            CivilianRecord loadedRec = CivilianRecord.load(recTag, level.registryAccess());
            helper.assertTrue(CivilianOccupations.occupationOf(loadedRec) == Occupation.FARMER,
                    "CivilianRecord NBT preserves FARMER occupation");
            helper.assertTrue(farmA.id().equals(loadedRec.schedule().work().workplaceRegionId()),
                    "CivilianRecord NBT preserves workplaceRegionId");

            c1.discard();
            replacement = CivilianSpawning.respawnBody(rec1, level);
            helper.assertTrue(replacement.occupation() == Occupation.FARMER
                    && farmA.id().equals(CivilianOccupations.workplaceIdOf(replacement)),
                    "replacement body preserves FARMER assignment");

            // Deleting Farm A by removing its last room detaches replacement back to UNEMPLOYED.
            FarmRegionScanner.RemoveRoomResult removed = FarmRegionScanner.removeRoomAt(
                    level, data, farmA, compA, level.getGameTime());
            helper.assertTrue(removed.outcome() == FarmRegionScanner.RemoveOutcome.DELETED_REGION, "Farm A deleted");
            helper.assertTrue(replacement.occupation() == Occupation.UNEMPLOYED,
                    "deleting Farm A unassigned its farmer");

            // Citizen death detaches from Farm B so another citizen can take the slot.
            helper.assertTrue(CivilianOccupations.assign(level, data, replacement, farmB).succeeded(),
                    "replacement assigned to Farm B");
            replacement.die(level.damageSources().generic());
            helper.assertTrue(farmB.workers().isEmpty(), "citizen death freed Farm B worker slot");
            helper.assertTrue(CivilianOccupations.assign(level, data, c2, farmB).succeeded(),
                    "c2 hired into freed Farm B slot");
            helper.succeed();
        } finally {
            LedgerSelection.clear(level, founder);
            c1.discard();
            c2.discard();
            if (replacement != null) {
                replacement.discard();
            }
            disband(helper, empire);
            clearBlocks(level, placed);
        }
    }

    private FarmOccupationGameTests() {
    }
}
