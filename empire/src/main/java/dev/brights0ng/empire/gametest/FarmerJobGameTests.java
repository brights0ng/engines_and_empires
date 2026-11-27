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
import dev.brights0ng.empire.civilian.ai.FarmWorkScanner;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.ledger.LedgerSelection;
import dev.brights0ng.empire.region.RegionManager;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianSkills;
import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.empire.rules.ScheduleClock;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Integration tests for Farmer job mechanics: harvesting &amp; replanting, dumping produce into shed storage while
 * preserving personal meals, non-shrinking fields + hoe fetching &amp; re-tilling + pure seeding, majority crop &amp;
 * offhand ledger crop override/clear, end-of-shift work gear stowage, and emergency-only raw produce eating.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class FarmerJobGameTests {

    private static final long TEST_SCHEDULE_DAY = 30L;

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
        // Simple (0.25) reproduces the 1.0x baseline speed with zero rookie fumbles for deterministic AI flow tests.
        c.skills().setAllLevels(CivilianSkills.ROOKIE_SKILL_CEILING);
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

    private static PlayerInteractEvent.RightClickBlock rightClick(Player player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
    }

    private static int countInStorage(ServerLevel level, BlockPos storagePos, Item item) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, storagePos, null);
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.is(item)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static int countHoesInStorage(ServerLevel level, BlockPos storagePos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, storagePos, null);
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (dev.brights0ng.empire.civilian.ai.FarmCrops.isHoe(handler.getStackInSlot(i))) {
                total++;
            }
        }
        return total;
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "farmer_harvest_dump", timeoutTicks = 320)
    public static void farmerHarvestsReplantsAndDumpsFullPocketsKeepingPersonalMeal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 8, placed);

        // Shed: composter + chest.
        BlockPos comp = main.offset(2, 0, 0);
        BlockPos chest = comp.east();
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest, Blocks.CHEST.defaultBlockState(), placed);

        // Field: 3 hydrated mature potato plots right south of the shed.
        BlockPos f0 = comp.offset(0, -1, 2);
        BlockPos f1 = f0.east();
        BlockPos f2 = f1.east();
        BlockState wetFarm = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
        BlockState ripePotato = Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 7);
        for (BlockPos soil : new BlockPos[] {f0, f1, f2}) {
            setBlock(level, soil, wetFarm, placed);
            setBlock(level, soil.above(), ripePotato, placed);
        }

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity farmer = spawnCitizen(helper, empire, comp.north());
        // Pre-fill 7 slots with potatoes + 1 slot with personal bread (1 slot free -> fills on harvest, triggers dump!).
        farmer.inventory().setItem(0, new ItemStack(Items.BREAD, 3));
        for (int s = 1; s <= 7; s++) {
            farmer.inventory().setItem(s, new ItemStack(Items.POTATO, 64));
        }

        rightClick(founder, comp);
        RegionRecord farm = data.regionAt(comp);
        helper.assertTrue(farm != null && farm.valid(), "farm created");
        rightClick(founder, f0);
        helper.assertTrue(CivilianOccupations.assign(level, data, farmer, farm).succeeded(), "farmer assigned");

        helper.runAtTickTime(290, () -> {
            try {
                int replanted = 0;
                for (BlockPos soil : new BlockPos[] {f0, f1, f2}) {
                    BlockState crop = level.getBlockState(soil.above());
                    if (crop.is(Blocks.POTATOES) && crop.getValue(CropBlock.AGE) < 7) {
                        replanted++;
                    }
                }
                helper.assertTrue(replanted >= 1, "farmer harvested and replanted potatoes, replanted=" + replanted);
                int chestPotatoes = countInStorage(level, chest, Items.POTATO);
                helper.assertTrue(chestPotatoes >= 64,
                        "farmer dumped potatoes into shed chest, chestPotatoes=" + chestPotatoes);
                helper.assertTrue(farmer.inventory().countItem(Items.BREAD) == 3,
                        "personal bread meal kept in pockets");
                helper.assertTrue(countInStorage(level, chest, Items.BREAD) == 0,
                        "personal bread was not dumped into shed chest");
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

    @GameTest(template = GameTestStructures.EMPTY, batch = "farmer_retill_seed", timeoutTicks = 300)
    public static void fieldDoesNotShrinkOnTrampleFarmerFetchesHoeRetillsAndSeedsFreshField(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 8, placed);

        BlockPos comp = main.offset(2, 0, 0);
        BlockPos chest = comp.east();
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest, Blocks.CHEST.defaultBlockState(), placed);

        // Put an iron hoe and 16 wheat seeds in the shed chest.
        IItemHandler chestHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        helper.assertTrue(chestHandler != null, "chest exposes ItemHandler capability");
        chestHandler.insertItem(0, new ItemStack(Items.IRON_HOE), false);
        chestHandler.insertItem(1, new ItemStack(Items.WHEAT_SEEDS, 16), false);

        BlockPos f0 = comp.offset(0, -1, 2);
        BlockPos f1 = f0.east();
        BlockPos f2 = f1.east();
        BlockState wetFarm = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
        for (BlockPos soil : new BlockPos[] {f0, f1, f2}) {
            setBlock(level, soil, wetFarm, placed);
        }

        rightClick(founder, comp);
        RegionRecord farm = data.regionAt(comp);
        rightClick(founder, f0);
        helper.assertTrue(farm != null && farm.roomAt(f0) != null && farm.roomAt(f0).blockCount() == 6,
                "field starts with 3 farmland + 3 air above = 6 blocks");

        // Trample f1 into dirt and run auto-rescan -> field room must NOT shrink!
        setBlock(level, f1, Blocks.DIRT.defaultBlockState(), placed);
        RegionManager.updateRegion(level, data, farm, level.getGameTime());
        helper.assertTrue(farm.containsBlock(f1) && farm.roomAt(f1) != null && farm.roomAt(f1).isField(),
                "trampled dirt stayed in FIELD room after auto-rescan");

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity farmer = spawnCitizen(helper, empire, comp.north());
        helper.assertTrue(CivilianOccupations.assign(level, data, farmer, farm).succeeded(), "farmer assigned");

        helper.runAtTickTime(270, () -> {
            try {
                helper.assertTrue(level.getBlockState(f1).is(Blocks.FARMLAND),
                        "farmer fetched hoe and re-tilled trampled dirt back to farmland");
                int seeded = 0;
                for (BlockPos soil : new BlockPos[] {f0, f1, f2}) {
                    if (level.getBlockState(soil.above()).is(Blocks.WHEAT)) {
                        seeded++;
                    }
                }
                helper.assertTrue(seeded == 3, "farmer seeded all 3 plots with wheat, seeded=" + seeded);

                // Verify explicit ledger click DOES shrink a field when farmland is removed.
                setBlock(level, f2.above(), Blocks.AIR.defaultBlockState(), placed);
                setBlock(level, f2, Blocks.STONE.defaultBlockState(), placed);
                RegionManager.updateRegion(level, data, farm, level.getGameTime() + 25L);
                helper.assertTrue(farm.containsBlock(f2), "auto-rescan does not shrink field");
                rightClick(founder, f0); // explicit ledger click on selected farm's field
                helper.assertFalse(farm.containsBlock(f2), "explicit ledger re-scan shrinks removed field tile");
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

    @GameTest(template = GameTestStructures.EMPTY, batch = "farmer_crop_override", timeoutTicks = 180)
    public static void majorityCropAndOffhandLedgerOverridePreserveImmatureCropsAndReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 8, placed);
        BlockPos comp = main.offset(2, 0, 0);
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);

        BlockState wetFarm = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
        BlockState youngWheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 2);
        BlockState youngPotato = Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 2);

        BlockPos[] soils = {
                comp.offset(0, -1, 2), comp.offset(1, -1, 2), comp.offset(2, -1, 2),
                comp.offset(0, -1, 3), comp.offset(1, -1, 3)
        };
        for (BlockPos s : soils) {
            setBlock(level, s, wetFarm, placed);
        }
        // 3 young wheat, 1 young potato, 1 empty farmland -> majority is wheat_seeds!
        setBlock(level, soils[0].above(), youngWheat, placed);
        setBlock(level, soils[1].above(), youngWheat, placed);
        setBlock(level, soils[2].above(), youngWheat, placed);
        setBlock(level, soils[3].above(), youngPotato, placed);

        rightClick(founder, comp);
        RegionRecord farm = data.regionAt(comp);
        rightClick(founder, soils[0]);
        RegionRecord.RoomRecord field = farm.roomAt(soils[0]);
        helper.assertTrue(field != null && field.isField(), "field room created");

        // Majority check (auto): 3 wheat vs 1 potato -> WHEAT_SEEDS.
        helper.assertTrue(FarmWorkScanner.resolveTargetSeedForRoom(level, field, null) == Items.WHEAT_SEEDS,
                "auto majority resolves to wheat_seeds");

        // Move ledger to OFFHAND: click field with produce (WHEAT) -> sets override to minecraft:wheat_seeds!
        founder.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT));
        helper.assertTrue(rightClick(founder, soils[0]).isCanceled(), "offhand ledger + wheat intercepted");
        helper.assertTrue("minecraft:wheat_seeds".equals(farm.roomAt(soils[0]).preferredCropId()),
                "produce wheat mapped to wheat_seeds override");

        // Click with empty main hand (ledger in offhand) -> clears override back to auto!
        founder.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertTrue(rightClick(founder, soils[0]).isCanceled(), "offhand ledger + empty hand intercepted");
        helper.assertTrue(farm.roomAt(soils[0]).preferredCropId() == null, "empty hand cleared crop override");

        // Override to POTATO and let farmer plant the empty spot -> must plant POTATO and leave young wheat alone!
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.POTATO));
        helper.assertTrue(rightClick(founder, soils[0]).isCanceled(), "offhand ledger + potato intercepted");
        helper.assertTrue("minecraft:potato".equals(farm.roomAt(soils[0]).preferredCropId()),
                "override set to minecraft:potato");

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity farmer = spawnCitizen(helper, empire, soils[4].above());
        farmer.inventory().setItem(0, new ItemStack(Items.POTATO, 8));
        farmer.inventory().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 8));
        helper.assertTrue(CivilianOccupations.assign(level, data, farmer, farm).succeeded(), "farmer assigned");

        helper.runAtTickTime(140, () -> {
            try {
                helper.assertTrue(level.getBlockState(soils[4].above()).is(Blocks.POTATOES),
                        "farmer planted overridden crop (potato) into empty plot");
                helper.assertTrue(level.getBlockState(soils[0].above()).is(Blocks.WHEAT)
                        && level.getBlockState(soils[1].above()).is(Blocks.WHEAT)
                        && level.getBlockState(soils[2].above()).is(Blocks.WHEAT),
                        "farmer left immature wheat untouched to finish growing");
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

    @GameTest(template = GameTestStructures.EMPTY, batch = "farmer_end_shift", timeoutTicks = 200)
    public static void endOfShiftStowsHoeSeedsAndProduceWhileKeepingPersonalMeal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 8, placed);

        BlockPos comp = main.offset(2, 0, 0);
        BlockPos chest = comp.east();
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest, Blocks.CHEST.defaultBlockState(), placed);

        rightClick(founder, comp);
        RegionRecord farm = data.regionAt(comp);

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity farmer = spawnCitizen(helper, empire, comp.north());
        farmer.inventory().setItem(0, new ItemStack(Items.IRON_HOE));
        farmer.inventory().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 12));
        farmer.inventory().setItem(2, new ItemStack(Items.POTATO, 10));
        farmer.inventory().setItem(3, new ItemStack(Items.BREAD, 2));
        helper.assertTrue(CivilianOccupations.assign(level, data, farmer, farm).succeeded(), "farmer assigned");

        // Let the WORK slot start, then mark today's work quota complete so end-of-shift wrap-up runs!
        helper.runAtTickTime(25, () -> {
            int remaining = farmer.schedule().remainingWorkTicks();
            if (remaining > 0) {
                farmer.schedule().addWorkDoneTicks(remaining);
            }
        });

        helper.runAtTickTime(175, () -> {
            try {
                helper.assertTrue(countHoesInStorage(level, chest) == 1, "iron hoe stowed in shed chest at shift end");
                helper.assertTrue(countInStorage(level, chest, Items.WHEAT_SEEDS) == 12,
                        "wheat seeds stowed in shed chest at shift end");
                helper.assertTrue(countInStorage(level, chest, Items.POTATO) == 10,
                        "potatoes stowed in shed chest at shift end");
                helper.assertTrue(farmer.inventory().countItem(Items.BREAD) == 2,
                        "personal bread meal kept in pockets after shift end");
                helper.assertTrue(farmer.getMainHandItem().isEmpty(), "main hand stowed cleanly");
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

    @GameTest(template = GameTestStructures.EMPTY, batch = "farmer_raw_produce_eat", timeoutTicks = 40)
    public static void rawProduceIsEatenOnlyBelowSnackThresholdWhenOutOfProperFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity c = spawnCitizen(helper, empire, mainPos(helper));
        try {
            c.inventory().setItem(0, new ItemStack(Items.POTATO, 10));

            // Above snack threshold (14 > 10): refuses raw potato!
            c.setFood(14, 0.0F);
            helper.assertFalse(c.hasFittingPocketFood(), "does not treat raw potato as meal food at food=14");
            helper.assertFalse(c.startEatingFromInventory(true), "refuses to eat raw potato above snack threshold");

            // At/below snack threshold (9 <= 10) with no proper food: accepts raw potato!
            c.setFood(9, 0.0F);
            helper.assertTrue(c.hasFittingPocketFood(), "allows raw potato emergency snack at food=9");
            helper.assertTrue(c.startEatingFromInventory(true), "starts eating raw potato at food=9");
            c.stopUsingItem();

            // With proper food (bread) also present at food=9: prefers bread over raw potato!
            c.inventory().setItem(1, new ItemStack(Items.BREAD, 1));
            c.setFood(9, 0.0F);
            helper.assertTrue(c.startEatingFromInventory(true), "starts eating at food=9 with bread present");
            helper.assertTrue(c.getMainHandItem().is(Items.BREAD), "preferred proper bread over raw potato");
            c.stopUsingItem();
            helper.succeed();
        } finally {
            c.discard();
            disband(helper, empire);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "farmer_harvest_then_seed", timeoutTicks = 360)
    public static void farmerHarvestsAllFieldsBeforeSeedingWithMomentum(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 9, placed);

        BlockPos comp = main.offset(2, 0, 0);
        BlockPos chest = comp.east();
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest, Blocks.CHEST.defaultBlockState(), placed);

        BlockState wetFarm = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
        BlockState ripeWheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);

        // Field 1 (closer to shed): 3 mature wheat plots.
        BlockPos[] field1 = {comp.offset(0, -1, 2), comp.offset(1, -1, 2), comp.offset(2, -1, 2)};
        // Field 2 (separated by 1-block stone walkway -> distinct FIELD room): 2 mature wheat plots.
        BlockPos[] field2 = {comp.offset(0, -1, 4), comp.offset(1, -1, 4)};
        for (BlockPos s : field1) {
            setBlock(level, s, wetFarm, placed);
            setBlock(level, s.above(), ripeWheat, placed);
        }
        for (BlockPos s : field2) {
            setBlock(level, s, wetFarm, placed);
            setBlock(level, s.above(), ripeWheat, placed);
        }

        rightClick(founder, comp);
        RegionRecord farm = data.regionAt(comp);
        helper.assertTrue(farm != null && farm.valid(), "farm created");
        rightClick(founder, field1[0]);
        rightClick(founder, field2[0]);
        long fieldRooms = farm.rooms().stream().filter(RegionRecord.RoomRecord::isField).count();
        helper.assertTrue(fieldRooms == 2, "two distinct FIELD rooms scanned, got=" + fieldRooms);

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity farmer = spawnCitizen(helper, empire, comp.north());
        // Carry plenty of wheat seeds so seeding would be immediately eligible once Field 1 empties!
        farmer.inventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 16));
        helper.assertTrue(CivilianOccupations.assign(level, data, farmer, farm).succeeded(), "farmer assigned");

        boolean[] sawSeedBeforeAllHarvested = {false};
        boolean[] sawAllHarvestedBeforeAnySeeded = {false};
        for (int t = 10; t <= 320; t += 4) {
            helper.runAtTickTime(t, () -> {
                int ripeLeft = 0;
                int youngPlanted = 0;
                for (BlockPos[] f : new BlockPos[][] {field1, field2}) {
                    for (BlockPos s : f) {
                        BlockState crop = level.getBlockState(s.above());
                        if (crop.is(Blocks.WHEAT)) {
                            if (crop.getValue(CropBlock.AGE) == 7) {
                                ripeLeft++;
                            } else {
                                youngPlanted++;
                            }
                        }
                    }
                }
                if (youngPlanted > 0 && ripeLeft > 0) {
                    sawSeedBeforeAllHarvested[0] = true;
                }
                if (ripeLeft == 0 && youngPlanted == 0) {
                    sawAllHarvestedBeforeAnySeeded[0] = true;
                }
            });
        }

        helper.runAtTickTime(330, () -> {
            try {
                helper.assertFalse(sawSeedBeforeAllHarvested[0],
                        "farmer must harvest every mature crop across both fields before seeding any plot");
                helper.assertTrue(sawAllHarvestedBeforeAnySeeded[0],
                        "observed full harvest completion across both fields before seeding began");
                int seeded = 0;
                for (BlockPos[] f : new BlockPos[][] {field1, field2}) {
                    for (BlockPos s : f) {
                        BlockState crop = level.getBlockState(s.above());
                        if (crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) < 7) {
                            seeded++;
                        }
                    }
                }
                helper.assertTrue(seeded == 5, "farmer seeded all 5 plots after finishing harvest, seeded=" + seeded);
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

    @GameTest(template = GameTestStructures.EMPTY, batch = "farmer_count_choice", timeoutTicks = 320)
    public static void farmerChoosesSeedingFirstWhenMoreSeedableFarmlandThanRipeCrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 9, placed);

        BlockPos comp = main.offset(2, 0, 0);
        BlockPos chest = comp.east();
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest, Blocks.CHEST.defaultBlockState(), placed);

        // Put seeds in the shed chest (0 in pockets) so farmer must choose Seed by count and fetch seeds first!
        IItemHandler chestHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        helper.assertTrue(chestHandler != null, "chest exposes ItemHandler");
        chestHandler.insertItem(0, new ItemStack(Items.WHEAT_SEEDS, 16), false);

        BlockState wetFarm = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
        BlockState ripeWheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);

        // Field 1: 4 empty farmland plots; Field 2: 2 mature wheat plots (4 > 2 -> Seed must be chosen first!).
        BlockPos[] emptyField = {
                comp.offset(0, -1, 2), comp.offset(1, -1, 2), comp.offset(2, -1, 2), comp.offset(3, -1, 2)
        };
        BlockPos[] ripeField = {comp.offset(0, -1, 4), comp.offset(1, -1, 4)};
        for (BlockPos s : emptyField) {
            setBlock(level, s, wetFarm, placed);
        }
        for (BlockPos s : ripeField) {
            setBlock(level, s, wetFarm, placed);
            setBlock(level, s.above(), ripeWheat, placed);
        }

        rightClick(founder, comp);
        RegionRecord farm = data.regionAt(comp);
        rightClick(founder, emptyField[0]);
        rightClick(founder, ripeField[0]);

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity farmer = spawnCitizen(helper, empire, comp.north());
        helper.assertTrue(CivilianOccupations.assign(level, data, farmer, farm).succeeded(), "farmer assigned");

        boolean[] sawHarvestBeforeSeedingComplete = {false};
        boolean[] sawSeedingCompleteBeforeHarvest = {false};
        for (int t = 10; t <= 290; t += 4) {
            helper.runAtTickTime(t, () -> {
                int emptySeeded = 0;
                for (BlockPos s : emptyField) {
                    if (level.getBlockState(s.above()).is(Blocks.WHEAT)) {
                        emptySeeded++;
                    }
                }
                int ripeRemaining = 0;
                for (BlockPos s : ripeField) {
                    BlockState st = level.getBlockState(s.above());
                    if (st.is(Blocks.WHEAT) && st.getValue(CropBlock.AGE) == 7) {
                        ripeRemaining++;
                    }
                }
                if (ripeRemaining < 2 && emptySeeded < 4) {
                    sawHarvestBeforeSeedingComplete[0] = true;
                }
                if (emptySeeded == 4 && ripeRemaining == 2) {
                    sawSeedingCompleteBeforeHarvest[0] = true;
                }
            });
        }

        helper.runAtTickTime(300, () -> {
            try {
                helper.assertFalse(sawHarvestBeforeSeedingComplete[0],
                        "farmer must finish seeding empty field before harvesting ripe field when empty > ripe");
                helper.assertTrue(sawSeedingCompleteBeforeHarvest[0],
                        "observed all 4 empty plots seeded while both ripe crops were still untouched");
                int ripeStillStanding = 0;
                for (BlockPos s : ripeField) {
                    BlockState st = level.getBlockState(s.above());
                    if (st.is(Blocks.WHEAT) && st.getValue(CropBlock.AGE) == 7) {
                        ripeStillStanding++;
                    }
                }
                helper.assertTrue(ripeStillStanding == 0,
                        "after finishing seeding, farmer switched and harvested the ripe field");
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

    @GameTest(template = GameTestStructures.EMPTY, batch = "farmer_interrupt_momentum", timeoutTicks = 360)
    public static void overflowInterruptionDumpsRetillsAndPreservesHarvestMomentum(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long savedTime = level.getDayTime();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        buildFloor(level, main, 9, placed);

        BlockPos comp = main.offset(2, 0, 0);
        BlockPos chest = comp.east();
        setBlock(level, comp, Blocks.COMPOSTER.defaultBlockState(), placed);
        setBlock(level, chest, Blocks.CHEST.defaultBlockState(), placed);

        IItemHandler chestHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        helper.assertTrue(chestHandler != null, "chest exposes ItemHandler");
        chestHandler.insertItem(0, new ItemStack(Items.IRON_HOE), false);

        BlockState wetFarm = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
        BlockState ripeWheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);

        // 1 field with 4 ripe wheat plots; we'll trample plot 4 into dirt mid-harvest or right before harvest!
        BlockPos[] plots = {
                comp.offset(0, -1, 2), comp.offset(1, -1, 2), comp.offset(2, -1, 2), comp.offset(3, -1, 2)
        };
        BlockPos trampledSpot = comp.offset(0, -1, 3);
        for (BlockPos s : plots) {
            setBlock(level, s, wetFarm, placed);
            setBlock(level, s.above(), ripeWheat, placed);
        }
        setBlock(level, trampledSpot, wetFarm, placed);

        rightClick(founder, comp);
        RegionRecord farm = data.regionAt(comp);
        rightClick(founder, plots[0]);

        // Trample trampledSpot into dirt after scanning (stays in FIELD room).
        setBlock(level, trampledSpot, Blocks.DIRT.defaultBlockState(), placed);
        RegionManager.updateRegion(level, data, farm, level.getGameTime());

        level.setDayTime(dayTimeFor(TEST_SCHEDULE_DAY, ScheduleClock.mmss(6, 0)));
        CivilianEntity farmer = spawnCitizen(helper, empire, comp.north());
        // 8 slots filled with potatoes + 1 empty slot: harvesting wheat drops wheat (fills slot 8) + wheat_seeds (overflows!).
        for (int i = 0; i < 8; i++) {
            farmer.inventory().setItem(i, new ItemStack(Items.POTATO, 64));
        }
        helper.assertTrue(CivilianOccupations.assign(level, data, farmer, farm).succeeded(), "farmer assigned");

        helper.runAtTickTime(330, () -> {
            try {
                helper.assertTrue(level.getBlockState(trampledSpot).is(Blocks.FARMLAND),
                        "farmer fetched hoe and re-tilled reverted dirt during interruption");
                int ripeLeft = 0;
                for (BlockPos s : plots) {
                    BlockState st = level.getBlockState(s.above());
                    if (st.is(Blocks.WHEAT) && st.getValue(CropBlock.AGE) == 7) {
                        ripeLeft++;
                    }
                }
                helper.assertTrue(ripeLeft == 0,
                        "farmer resumed and finished harvesting all ripe wheat after dump+retill, ripeLeft=" + ripeLeft);
                helper.assertTrue(countInStorage(level, chest, Items.POTATO) >= 64,
                        "farmer dumped overflow potatoes into shed chest");
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

    private FarmerJobGameTests() {
    }
}
