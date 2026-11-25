// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.ledger.LedgerSelection;
import dev.brights0ng.empire.region.RegionManager;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.RoomLabel;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
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
 * Game tests for {@link RegionType#FARM} regions: composter seed sheds, storage marking, field flood fills with
 * tilled-dirt above-block propagation, same-tag merging, opposite-tag boundaries, ledger selection/deselection,
 * shift-right-click room removal, auto-reshaping, hazards, and 3-day expiry.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class FarmRegionGameTests {

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

    private static void setBlock(ServerLevel level, BlockPos pos, BlockState state, List<BlockPos> placed) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        placed.add(pos);
    }

    private static void clearBlocks(ServerLevel level, List<BlockPos> placed) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos pos : placed) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        }
    }

    private static PlayerInteractEvent.RightClickBlock rightClick(Player player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
    }

    private static PlayerInteractEvent.LeftClickBlock leftClick(Player player, BlockPos pos) {
        return CommonHooks.onLeftClickBlock(player, pos, Direction.UP,
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "farm_create_storage", timeoutTicks = 25)
    public static void composterCreatesAutoFarmShedWithStorageAndPersistsThroughNbt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        // Place an outdoor shed cluster (sky-exposed): composter + chest + trapped_chest + barrel, next to stone.
        BlockPos composter = mainPos(helper).offset(4, 0, 0);
        BlockPos chest = composter.east();
        BlockPos trappedChest = composter.south();
        BlockPos barrel = composter.above();
        BlockPos stone = composter.west();
        setBlock(level, composter, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest, Blocks.CHEST.defaultBlockState(), placed);
        setBlock(level, trappedChest, Blocks.TRAPPED_CHEST.defaultBlockState(), placed);
        setBlock(level, barrel, Blocks.BARREL.defaultBlockState(), placed);
        setBlock(level, stone, Blocks.STONE.defaultBlockState(), placed);

        helper.runAtTickTime(3, () -> {
            try {
                // Without selection, clicking chest or stone does not cancel vanilla interaction.
                helper.assertFalse(rightClick(founder, chest).isCanceled(), "chest click passes through with no selection");
                helper.assertFalse(rightClick(founder, stone).isCanceled(), "stone click passes through with no selection");

                // Right-clicking composter creates an AUTO FARM shed, ignores air/stone/sky, marks storage, and selects it.
                PlayerInteractEvent.RightClickBlock click = rightClick(founder, composter);
                helper.assertTrue(click.isCanceled(), "composter right-click canceled");

                RegionRecord farm = data.regionAt(composter);
                helper.assertTrue(farm != null, "FARM region created");
                helper.assertTrue(farm.type() == RegionType.FARM, "type is FARM");
                helper.assertTrue(farm.origin() == RegionOrigin.AUTO, "origin is AUTO");
                helper.assertTrue(farm.valid(), "FARM is valid immediately with seed shed");
                helper.assertTrue(farm.blockCount() == 4, "shed has exactly 4 blocks (no air/stone), got " + farm.blockCount());
                helper.assertFalse(farm.containsBlock(stone), "stone excluded");
                helper.assertFalse(farm.containsBlock(composter.north()), "air excluded");
                helper.assertTrue(farm.rooms().size() == 1, "1 room");
                RegionRecord.RoomRecord shed = farm.rooms().get(0);
                helper.assertTrue(shed.isShed() && !shed.isIndoor() && !shed.isOutdoor(), "room tagged SHED only");
                helper.assertTrue(shed.seed() == composter.asLong(), "room seed is composter");

                // Storage blocks marked on both room and region (composter is not storage).
                helper.assertTrue(farm.storageBlocks().size() == 3, "3 storage blocks marked, got " + farm.storageBlocks().size());
                helper.assertTrue(farm.isStorageBlock(chest), "chest marked storage");
                helper.assertTrue(farm.isStorageBlock(trappedChest), "trapped chest marked storage");
                helper.assertTrue(farm.isStorageBlock(barrel), "barrel marked storage");
                helper.assertFalse(farm.isStorageBlock(composter), "composter is not storage");

                // Farm is now selected for founder.
                helper.assertTrue(farm.id().equals(LedgerSelection.selectedRegionId(founder.getUUID())),
                        "new farm is selected");

                // NBT round-trip preserves type, rooms, per-room seed, labels, and storage blocks.
                CompoundTag saved = farm.save();
                RegionRecord loaded = RegionRecord.load(saved);
                helper.assertTrue(loaded != null && loaded.type() == RegionType.FARM, "loaded FARM");
                helper.assertTrue(loaded.rooms().size() == 1, "loaded 1 room");
                helper.assertTrue(loaded.rooms().get(0).isShed(), "loaded SHED label");
                helper.assertTrue(loaded.rooms().get(0).seed() == composter.asLong(), "loaded room seed");
                helper.assertTrue(loaded.storageBlocks().size() == 3 && loaded.isStorageBlock(barrel),
                        "loaded storage blocks");
            } finally {
                LedgerSelection.clear(level, founder);
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "farm_fields_merge_boundaries", timeoutTicks = 30)
    public static void selectedFarmAddsFieldsWithTilledDirtAbovePropagationMergesSameTagAndRespectsBoundaries(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos base = mainPos(helper).offset(4, 0, 0);
        // Shed 1: composter + chest.
        BlockPos composter = base;
        BlockPos chest1 = base.east();
        setBlock(level, composter, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest1, Blocks.CHEST.defaultBlockState(), placed);

        // Shed 2 (initially separated from Shed 1 by 1 gap at base.east(2)).
        BlockPos barrel2 = base.east(3);
        setBlock(level, barrel2, Blocks.BARREL.defaultBlockState(), placed);

        // Field 1: farmland directly touching chest1 on the south!
        // Includes: farmland with wheat above, farmland with air above, farmland with oak fence above (any block!),
        // and terraced farmland 1 block higher adjacent to the above-block.
        BlockPos farm1 = chest1.south();
        BlockPos farm2 = farm1.east();
        BlockPos farm3 = farm2.east();
        BlockPos terraceFarm = farm3.east().above(); // 1 block higher, touches farm3.above()!
        setBlock(level, farm1.below(), Blocks.DIRT.defaultBlockState(), placed);
        setBlock(level, farm2.below(), Blocks.DIRT.defaultBlockState(), placed);
        setBlock(level, farm3.below(), Blocks.DIRT.defaultBlockState(), placed);
        setBlock(level, terraceFarm.below(), Blocks.DIRT.defaultBlockState(), placed);
        setBlock(level, farm1, Blocks.FARMLAND.defaultBlockState(), placed);
        setBlock(level, farm1.above(), Blocks.WHEAT.defaultBlockState(), placed);
        setBlock(level, farm2, Blocks.FARMLAND.defaultBlockState(), placed);
        // farm2.above() is air — still whitelisted because it is above farmland!
        setBlock(level, farm3, Blocks.FARMLAND.defaultBlockState(), placed);
        setBlock(level, farm3.above(), Blocks.OAK_FENCE_GATE.defaultBlockState(), placed); // arbitrary non-crop block allowed on farmland
        setBlock(level, terraceFarm, Blocks.FARMLAND.defaultBlockState(), placed);
        // terraceFarm.above() is air — also whitelisted!

        // Separate Field 2 (sweet berry bush) further south.
        BlockPos berry = base.south(4);
        setBlock(level, berry.below(), Blocks.GRASS_BLOCK.defaultBlockState(), placed);
        setBlock(level, berry, Blocks.SWEET_BERRY_BUSH.defaultBlockState(), placed);

        helper.runAtTickTime(3, () -> {
            try {
                // 1. Create & select farm at composter.
                helper.assertTrue(rightClick(founder, composter).isCanceled(), "create farm");
                RegionRecord farm = data.regionAt(composter);
                helper.assertTrue(farm != null && farm.roomCountWithLabel(RoomLabel.SHED) == 1, "1 shed");

                // 2. Add Shed 2 while selected.
                helper.assertTrue(rightClick(founder, barrel2).isCanceled(), "add shed 2");
                helper.assertTrue(farm.roomCountWithLabel(RoomLabel.SHED) == 2, "2 separate sheds");
                helper.assertTrue(farm.storageBlocks().size() == 2, "2 storage blocks across both sheds");

                // 3. Add Field 1 touching Shed 1 -> Shed 1 acts as boundary; all 4 farmland + 4 blocks above = 8 blocks!
                helper.assertTrue(rightClick(founder, farm1).isCanceled(), "add field 1");
                helper.assertTrue(farm.roomCountWithLabel(RoomLabel.FIELD) == 1, "1 field added");
                RegionRecord.RoomRecord fieldRoom = farm.roomAt(farm1);
                helper.assertTrue(fieldRoom != null && fieldRoom.isField(), "room tagged FIELD");
                helper.assertTrue(fieldRoom.blockCount() == 8,
                        "field has 4 farmland + 4 above-blocks (wheat, air, oak fence, terrace air), got " + fieldRoom.blockCount());
                helper.assertTrue(fieldRoom.blocks().contains(farm1.above().asLong()), "wheat above farmland included");
                helper.assertTrue(fieldRoom.blocks().contains(farm2.above().asLong()), "air above farmland included");
                helper.assertTrue(fieldRoom.blocks().contains(farm3.above().asLong()), "fence above farmland included");
                helper.assertTrue(fieldRoom.blocks().contains(terraceFarm.asLong()), "terraced farmland reached via above-block");
                helper.assertTrue(fieldRoom.blocks().contains(terraceFarm.above().asLong()), "air above terrace included");
                helper.assertFalse(farm.containsBlock(farm2.above(2)), "does not leak into air 2 blocks above farmland");
                helper.assertTrue(farm.roomAt(chest1).isShed(), "touching shed chest stayed in SHED room");

                // 4. Add Field 2 (sweet berry bush).
                helper.assertTrue(rightClick(founder, berry).isCanceled(), "add berry field");
                helper.assertTrue(farm.roomCountWithLabel(RoomLabel.FIELD) == 2, "2 separate fields");

                // 5. Bridge Shed 1 and Shed 2 with a new barrel and click it -> merges into 1 SHED room!
                BlockPos bridge = base.east(2);
                setBlock(level, bridge, Blocks.BARREL.defaultBlockState(), placed);
                helper.assertTrue(rightClick(founder, bridge).isCanceled(), "add bridging barrel");
                helper.assertTrue(farm.roomCountWithLabel(RoomLabel.SHED) == 1,
                        "touching same-tag sheds merged into 1 shed, got " + farm.roomCountWithLabel(RoomLabel.SHED));
                helper.assertTrue(farm.roomAt(composter).blockCount() == 4, "merged shed has 4 blocks");
                helper.assertTrue(farm.storageBlocks().size() == 3, "merged shed has 3 storage blocks");
            } finally {
                LedgerSelection.clear(level, founder);
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "farm_selection_rules", timeoutTicks = 30)
    public static void selectionLifecycleLeftClickUnequipOffhandSwitchAndOtherRegionErrors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos compA = mainPos(helper).offset(4, 0, 0);
        BlockPos chestA = compA.east();
        BlockPos compB = mainPos(helper).offset(4, 0, 5);
        BlockPos chestB = compB.east();
        BlockPos wildStone = mainPos(helper).offset(4, 0, 3);
        setBlock(level, compA, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chestA, Blocks.CHEST.defaultBlockState(), placed);
        setBlock(level, compB, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chestB, Blocks.CHEST.defaultBlockState(), placed);
        setBlock(level, wildStone, Blocks.STONE.defaultBlockState(), placed);

        final RegionRecord[] refs = new RegionRecord[2];

        helper.runAtTickTime(3, () -> {
            // Create Farm A -> selected.
            helper.assertTrue(rightClick(founder, compA).isCanceled(), "create Farm A");
            refs[0] = data.regionAt(compA);
            helper.assertTrue(refs[0] != null && refs[0].id().equals(LedgerSelection.selectedRegionId(founder.getUUID())),
                    "Farm A selected");

            // Left-click exits selection and cancels block punch.
            helper.assertTrue(leftClick(founder, wildStone).isCanceled(), "left-click cancels while deselecting");
            helper.assertTrue(LedgerSelection.selectedRegionId(founder.getUUID()) == null, "selection cleared on left-click");

            // Left-click again with no selection passes through uncanceled.
            helper.assertFalse(leftClick(founder, wildStone).isCanceled(), "left-click passes through when nothing selected");

            // Create Farm B while unselected -> Farm B selected.
            helper.assertTrue(rightClick(founder, compB).isCanceled(), "create Farm B");
            refs[1] = data.regionAt(compB);
            helper.assertTrue(refs[1] != null && !refs[1].id().equals(refs[0].id()), "separate Farm B created");
            helper.assertTrue(refs[1].id().equals(LedgerSelection.selectedRegionId(founder.getUUID())), "Farm B selected");

            // Clicking Farm A's chest (non-home block of another region) while Farm B is selected -> canceled error, stays on Farm B.
            helper.assertTrue(rightClick(founder, chestA).isCanceled(), "other region's chest intercepted while selected");
            helper.assertTrue(refs[1].id().equals(LedgerSelection.selectedRegionId(founder.getUUID())),
                    "selection stayed on Farm B after clicking Farm A's chest");

            // Clicking Farm A's composter (home block of Farm A) while Farm B is selected -> switches selection to Farm A!
            helper.assertTrue(rightClick(founder, compA).isCanceled(), "other farm's composter intercepted");
            helper.assertTrue(refs[0].id().equals(LedgerSelection.selectedRegionId(founder.getUUID())),
                    "selection switched to Farm A");

            // Clicking non-home wild stone while Farm A is selected -> interacts normally (uncanceled).
            helper.assertFalse(rightClick(founder, wildStone).isCanceled(), "wild stone passes through while selected");

            // Clicking Farm A's own chest while Farm A is selected -> rescans & cancels vanilla chest open.
            helper.assertTrue(rightClick(founder, chestA).isCanceled(), "own room click cancels and rescans");

            // Move ledger to offhand -> selection stays active across ticks!
            founder.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            founder.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        });

        helper.runAtTickTime(6, () -> {
            helper.assertTrue(refs[0].id().equals(LedgerSelection.selectedRegionId(founder.getUUID())),
                    "holding ledger in offhand keeps selection active");
            // Unequip ledger from both hands -> selection clears on next server tick.
            founder.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        });

        helper.runAtTickTime(9, () -> {
            try {
                helper.assertTrue(LedgerSelection.selectedRegionId(founder.getUUID()) == null,
                        "unequipping ledger clears selection automatically");
                // Re-equip ledger, verify clicking Farm A's chest without selection passes through (does not reselect!).
                founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
                helper.assertFalse(rightClick(founder, chestA).isCanceled(),
                        "clicking chest of unselected farm interacts normally");
                helper.assertTrue(LedgerSelection.selectedRegionId(founder.getUUID()) == null,
                        "chest click did not select farm");
            } finally {
                LedgerSelection.clear(level, founder);
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "farm_shift_remove", timeoutTicks = 25)
    public static void shiftRightClickRemovesRoomsInvalidatesWithoutShedAndDeletesWhenEmpty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos comp = mainPos(helper).offset(4, 0, 0);
        BlockPos farmTile = comp.south(2);
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, farmTile.below(), Blocks.DIRT.defaultBlockState(), placed);
        setBlock(level, farmTile, Blocks.FARMLAND.defaultBlockState(), placed);

        helper.runAtTickTime(3, () -> {
            try {
                rightClick(founder, comp);
                RegionRecord farm = data.regionAt(comp);
                helper.assertTrue(farm != null && farm.valid(), "farm created");
                rightClick(founder, farmTile);
                helper.assertTrue(farm.rooms().size() == 2, "shed + field");

                // Shift-right-click the shed -> shed removed, field remains, farm becomes INVALID(NO_SHED).
                founder.setShiftKeyDown(true);
                helper.assertTrue(rightClick(founder, comp).isCanceled(), "shift-click shed canceled");
                helper.assertTrue(data.region(farm.id()) != null, "farm still exists with remaining field");
                helper.assertTrue(farm.rooms().size() == 1 && farm.roomCountWithLabel(RoomLabel.FIELD) == 1,
                        "only field room remains");
                helper.assertFalse(farm.valid(), "farm invalid without shed");
                helper.assertTrue(farm.invalidReason() == RegionInvalidReason.NO_SHED,
                        "reason is NO_SHED, got " + farm.invalidReason());
                helper.assertTrue(RegionManager.hasScheduledExpiry(farm.id()), "3-day expiry scheduled for AUTO farm");

                // Unselect the invalid shedless farm, then right-click its remaining field -> selects the invalid farm!
                founder.setShiftKeyDown(false);
                LedgerSelection.clear(level, founder);
                helper.assertTrue(LedgerSelection.selectedRegionId(founder.getUUID()) == null, "unselected");
                helper.assertTrue(rightClick(founder, farmTile).isCanceled(), "clicking invalid field handled");
                helper.assertTrue(farm.id().equals(LedgerSelection.selectedRegionId(founder.getUUID())),
                        "clicking anywhere in invalidated farm reselects it");

                // Shift-right-click the last remaining room (field) -> deletes the farm and clears selection!
                founder.setShiftKeyDown(true);
                helper.assertTrue(rightClick(founder, farmTile).isCanceled(), "shift-click last room canceled");
                helper.assertTrue(data.region(farm.id()) == null, "farm deleted when last room removed");
                helper.assertTrue(LedgerSelection.selectedRegionId(founder.getUUID()) == null,
                        "selection cleared on farm deletion");
            } finally {
                founder.setShiftKeyDown(false);
                LedgerSelection.clear(level, founder);
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "farm_reshape_hazards_recovery", timeoutTicks = 40)
    public static void farmAutoReshapesOnComposterBreakRecoversWithNewComposterAndChecksHazards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos comp = mainPos(helper).offset(4, 0, 0);
        BlockPos chest = comp.east();
        BlockPos farmTile = comp.south(3);
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest, Blocks.CHEST.defaultBlockState(), placed);
        setBlock(level, farmTile.below(), Blocks.DIRT.defaultBlockState(), placed);
        setBlock(level, farmTile, Blocks.FARMLAND.defaultBlockState(), placed);

        final RegionRecord[] farmRef = new RegionRecord[1];

        helper.runAtTickTime(3, () -> {
            rightClick(founder, comp);
            farmRef[0] = data.regionAt(comp);
            rightClick(founder, farmTile);
            LedgerSelection.clear(level, founder);
            helper.assertTrue(farmRef[0] != null && farmRef[0].valid() && farmRef[0].blockCount() == 4,
                    "initial farm valid with 2 shed + 2 field blocks");

            // Break the composter -> chest remains; shed auto-reshapes and farm stays VALID!
            setBlock(level, comp, Blocks.AIR.defaultBlockState(), placed);
            RegionManager.updateRegion(level, data, farmRef[0], level.getGameTime());
            helper.assertTrue(farmRef[0].valid(), "farm stays valid after composter broken while chest remains");
            helper.assertTrue(farmRef[0].roomCountWithLabel(RoomLabel.SHED) == 1, "shed room preserved");
            helper.assertTrue(farmRef[0].isStorageBlock(chest), "chest still marked storage");
            helper.assertFalse(farmRef[0].containsBlock(comp), "broken composter removed from region");

            // Cannot reselect via chest (not a composter).
            helper.assertFalse(rightClick(founder, chest).isCanceled(), "chest does not reselect farm");
            helper.assertTrue(LedgerSelection.selectedRegionId(founder.getUUID()) == null, "still unselected");

            // Place a new composter adjacent to the chest and immediately click it -> attaches & selects the same farm!
            BlockPos newComp = chest.east();
            setBlock(level, newComp, Blocks.COMPOSTER.defaultBlockState(), placed);
            helper.assertTrue(rightClick(founder, newComp).isCanceled(), "clicking newly placed adjacent composter handled");
            helper.assertTrue(farmRef[0].containsBlock(newComp), "shed auto-reshaped to include new composter");
            helper.assertTrue(farmRef[0].id().equals(LedgerSelection.selectedRegionId(founder.getUUID())),
                    "reselected existing farm via new composter");
            helper.assertTrue(data.regionsOf(empire.id()).size() == 1, "did not create a second farm");

            // Place TNT 6-adjacent to the field -> invalidates with HAZARD!
            BlockPos tnt = farmTile.west();
            setBlock(level, tnt, Blocks.TNT.defaultBlockState(), placed);
            RegionManager.updateRegion(level, data, farmRef[0], level.getGameTime());
            helper.assertFalse(farmRef[0].valid(), "TNT adjacent to field invalidates farm");
            helper.assertTrue(farmRef[0].invalidReason() == RegionInvalidReason.HAZARD, "reason is HAZARD");

            // Remove TNT -> recovers to VALID!
            setBlock(level, tnt, Blocks.AIR.defaultBlockState(), placed);
            RegionManager.updateRegion(level, data, farmRef[0], level.getGameTime());
            helper.assertTrue(farmRef[0].valid(), "removing TNT restores farm validity");

            // Break ALL blocks of the shed (chest + newComp) -> INVALID(NO_SHED), keeping bounds for repair.
            setBlock(level, chest, Blocks.AIR.defaultBlockState(), placed);
            setBlock(level, newComp, Blocks.AIR.defaultBlockState(), placed);
            RegionManager.updateRegion(level, data, farmRef[0], level.getGameTime());
            helper.assertFalse(farmRef[0].valid(), "breaking entire shed invalidates farm");
            helper.assertTrue(farmRef[0].invalidReason() == RegionInvalidReason.NO_SHED, "reason is NO_SHED");

            // Place a composter adjacent to the old shed footprint -> recovers to VALID!
            BlockPos repairComp = chest.north();
            setBlock(level, repairComp, Blocks.COMPOSTER.defaultBlockState(), placed);
            RegionManager.updateRegion(level, data, farmRef[0], level.getGameTime());
            helper.assertTrue(farmRef[0].valid(), "placing composter adjacent to broken shed recovers farm");
            helper.assertTrue(farmRef[0].containsBlock(repairComp), "recovered shed includes repair composter");

            // Verify 3-day auto-deletion for AUTO farm left invalid.
            setBlock(level, repairComp, Blocks.AIR.defaultBlockState(), placed);
            long t0 = level.getGameTime();
            RegionManager.updateRegion(level, data, farmRef[0], t0);
            helper.assertFalse(farmRef[0].valid(), "invalidated again");
            RegionManager.updateRegion(level, data, farmRef[0], t0 + RegionRules.INVALID_AUTO_DELETE_TICKS);
            helper.assertTrue(data.region(farmRef[0].id()) == null, "AUTO farm deleted after 3 MC days invalid");

            LedgerSelection.clear(level, founder);
            disband(helper, empire);
            clearBlocks(level, placed);
            helper.succeed();
        });
    }

    private FarmRegionGameTests() {
    }
}
