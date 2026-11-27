// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.civilian.ai.AiContext;
import dev.brights0ng.empire.civilian.ai.BreakBlockAction;
import dev.brights0ng.empire.civilian.ai.CivilianAction;
import dev.brights0ng.empire.civilian.ai.FarmCrops;
import dev.brights0ng.empire.civilian.ai.InteractBlockAction;
import dev.brights0ng.empire.civilian.ai.PlaceBlockAction;
import dev.brights0ng.empire.civilian.ai.TransferItemsAction;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianFood;
import dev.brights0ng.empire.rules.CivilianSkill;
import dev.brights0ng.empire.rules.CivilianSkills;
import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;
import dev.brights0ng.empire.rules.FarmerUtility;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Integration tests for the cross-job occupational skills system: trait-aligned traveller starting skills, v5
 * {@link CivilianRecord} persistence &amp; legacy v4 migration, Level-4 action speed/exhaustion/durability/yield/XP
 * scaling, non-destructive rookie fumbles (distinct from pocket overflow), and active-day decay with grace period.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianSkillsGameTests {

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

    private static AiContext aiContext(ServerLevel level, CivilianEntity civ, EmpireRecord empire) {
        return AiContext.of(level, civ, EmpireSavedData.of(level), empire);
    }

    private static CivilianAction.Status runActionToCompletion(CivilianAction action, AiContext ctx, int maxTicks) {
        action.start(ctx);
        CivilianAction.Status status = CivilianAction.Status.RUNNING;
        for (int i = 0; i < maxTicks && status == CivilianAction.Status.RUNNING; i++) {
            status = action.tick(ctx);
        }
        action.stop(ctx);
        return status;
    }

    private static void clearBlocks(ServerLevel level, List<BlockPos> placed) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos pos : placed) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        }
    }

    private static void clearDrops(ServerLevel level, BlockPos center, double radius) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius))) {
            item.discard();
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_skills_persist", timeoutTicks = 40)
    public static void startingSkillsPersistMigrateAndSurviveRespawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);
        BlockPos main = mainPos(helper);
        CivilianEntity c = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main), null);
        CivilianEntity replacement = null;
        try {
            // Freshly spawned civilian arrives with 1..3 trait-aligned starting skills in [0.05, 0.50].
            List<CivilianSkill> rolled = c.skills().nonZeroSkillsSorted();
            helper.assertTrue(rolled.size() >= 1 && rolled.size() <= 3,
                    "spawned civilian has 1..3 starting skills, got " + rolled.size());
            for (CivilianSkill s : CivilianSkill.values()) {
                double lvl = c.skills().level(s);
                helper.assertTrue(lvl >= 0.0 && lvl <= CivilianSkills.START_SKILL_MAX_LEVEL + 1e-9,
                        "starting skill within 0..0.5, got " + lvl);
            }

            CivilianRecord record = CivilianRoster.recruit(c, empire, level);
            helper.assertTrue(CivilianRecord.VERSION == 5, "CivilianRecord bumped to v5");
            helper.assertTrue(record.skills().equals(c.skills().save()), "recruit copies starting skills into record");

            // Mutate skills and verify record NBT round-trip & respawnBody preservation.
            c.skills().clearAll();
            c.skills().setLevel(CivilianSkill.HARVESTING, 0.60);
            c.skills().addRawXp(CivilianSkill.LOGISTICS, 125.0);
            c.skills().advanceDays(2); // HARVESTING -> 2 inactive days, LOGISTICS -> 1 inactive day after usedToday clear
            c.syncNeedsToRecord();

            CompoundTag savedTag = record.save(level.registryAccess());
            CivilianRecord reloaded = CivilianRecord.load(savedTag, level.registryAccess());
            helper.assertTrue(Math.abs(reloaded.skills().level(CivilianSkill.HARVESTING) - 0.60) < 1e-6,
                    "Harvesting level survives record NBT");
            helper.assertTrue(reloaded.skills().entry(CivilianSkill.HARVESTING).inactiveDays() == 2,
                    "inactiveDays survives record NBT");
            helper.assertTrue(Math.abs(reloaded.skills().entry(CivilianSkill.LOGISTICS).xp() - 125.0) < 1e-6,
                    "Logistics XP survives record NBT");

            // Debug info (sneak-click / info) and skills debug report include skills.
            String infoJoined = String.join("\n", c.debugInfoLines(level).stream().map(Component::getString).toList());
            helper.assertTrue(infoJoined.contains("Skills:") && infoJoined.contains("Harvesting: Competent (0.60)"),
                    "debugInfoLines includes compact skills summary: " + infoJoined);
            String skillsJoined = String.join("\n", c.skillsDebugLines(level).stream().map(Component::getString).toList());
            helper.assertTrue(skillsJoined.contains("=== Skills:") && skillsJoined.contains("Cultivation:"),
                    "skillsDebugLines includes full per-skill breakdown");

            // Intentionally cleared skills save with version marker and reload empty (NOT re-rolled).
            c.skills().clearAll();
            c.syncNeedsToRecord();
            CompoundTag emptyTag = record.save(level.registryAccess());
            CivilianRecord reloadedEmpty = CivilianRecord.load(emptyTag, level.registryAccess());
            helper.assertTrue(reloadedEmpty.skills().equals(CivilianSkills.Saved.EMPTY),
                    "explicitly reset skills reload empty without re-rolling");

            // Legacy v4 record without "skills" key migrates deterministically with trait-aligned starting skills.
            savedTag.remove("skills");
            CivilianRecord migratedA = CivilianRecord.load(savedTag, level.registryAccess());
            CivilianRecord migratedB = CivilianRecord.load(savedTag, level.registryAccess());
            helper.assertTrue(migratedA.skills().equals(migratedB.skills()),
                    "legacy v4 migration is deterministic");
            helper.assertTrue(!migratedA.skills().entries().isEmpty(),
                    "legacy v4 citizen receives trait-rolled starting skills");
            for (CivilianSkill s : CivilianSkill.values()) {
                double lvl = migratedA.skills().level(s);
                helper.assertTrue(lvl <= CivilianSkills.START_SKILL_MAX_LEVEL + 1e-9,
                        "migrated starting skill <= 0.50");
            }

            // Replacement body restores saved skills cleanly.
            c.discard();
            replacement = CivilianSpawning.respawnBody(reloaded, level);
            helper.assertTrue(Math.abs(replacement.skills().level(CivilianSkill.HARVESTING) - 0.60) < 1e-6,
                    "respawnBody preserves Harvesting skill");
            helper.assertTrue(Math.abs(replacement.skills().xp(CivilianSkill.LOGISTICS) - 125.0) < 1e-6,
                    "respawnBody preserves Logistics XP");
            helper.succeed();
        } finally {
            c.discard();
            if (replacement != null) replacement.discard();
            disband(helper, empire);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_skills_actions", timeoutTicks = 60)
    public static void actionsScaleSpeedExhaustionProduceYieldAndAwardTimeEfficiencyXp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos main = mainPos(helper);
        List<BlockPos> placed = new ArrayList<>();
        CivilianEntity c = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main), null);
        try {
            c.setTraits(CivilianTraits.NEUTRAL);
            c.skills().setAllLevels(0.0);
            CivilianRoster.recruit(c, empire, level);
            c.setFood(20, 5.0F);
            c.setLastNight(1.0F);

            BlockPos soil = main.offset(1, -1, 0);
            BlockPos cropPos = soil.above();
            BlockPos chestPos = main.offset(-1, 0, 0);
            BlockState wetFarm = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
            BlockState ripeWheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
            level.setBlock(soil, wetFarm, Block.UPDATE_ALL);
            level.setBlock(cropPos, ripeWheat, Block.UPDATE_ALL);
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            placed.add(soil);
            placed.add(cropPos);
            placed.add(chestPos);

            AiContext ctx = aiContext(level, c, empire);

            // 1. Speed & exhaustion scaling across 0.0 -> 0.25 -> 1.0 on Harvesting.
            BreakBlockAction break0 = new BreakBlockAction(cropPos, null, FarmerUtility.HARVEST_BASE_TICKS,
                    FarmerUtility.HARVEST_EXHAUSTION, true, FarmCrops::isMatureCrop,
                    CivilianSkill.HARVESTING, CivilianSkills.HARVEST_CROP_BASE_XP, true);
            break0.start(ctx);
            int dur0 = break0.durationTicks();
            break0.stop(ctx);

            c.skills().setLevel(CivilianSkill.HARVESTING, 0.25);
            BreakBlockAction breakSimple = new BreakBlockAction(cropPos, null, FarmerUtility.HARVEST_BASE_TICKS,
                    FarmerUtility.HARVEST_EXHAUSTION, true, FarmCrops::isMatureCrop,
                    CivilianSkill.HARVESTING, CivilianSkills.HARVEST_CROP_BASE_XP, true);
            double exhBeforeSimple = c.food().exhaustion();
            double xpBeforeSimple = c.skills().xp(CivilianSkill.HARVESTING);
            helper.assertTrue(runActionToCompletion(breakSimple, ctx, 40) == CivilianAction.Status.SUCCEEDED,
                    "Simple harvest succeeded");
            int durSimple = breakSimple.durationTicks();
            double exhDeltaSimple = c.food().exhaustion() - exhBeforeSimple;
            double xpGainSimple = c.skills().xp(CivilianSkill.HARVESTING) - xpBeforeSimple;

            level.setBlock(cropPos, ripeWheat, Block.UPDATE_ALL);
            c.skills().setLevel(CivilianSkill.HARVESTING, 0.75);
            double xpBeforeAdv = c.skills().xp(CivilianSkill.HARVESTING);
            BreakBlockAction breakAdv = new BreakBlockAction(cropPos, null, FarmerUtility.HARVEST_BASE_TICKS,
                    FarmerUtility.HARVEST_EXHAUSTION, true, FarmCrops::isMatureCrop,
                    CivilianSkill.HARVESTING, CivilianSkills.HARVEST_CROP_BASE_XP, true);
            helper.assertTrue(runActionToCompletion(breakAdv, ctx, 40) == CivilianAction.Status.SUCCEEDED,
                    "Advanced harvest succeeded");
            double xpGainAdv = c.skills().xp(CivilianSkill.HARVESTING) - xpBeforeAdv;

            level.setBlock(cropPos, ripeWheat, Block.UPDATE_ALL);
            c.skills().setLevel(CivilianSkill.HARVESTING, 1.0);
            double exhBeforeMaster = c.food().exhaustion();
            BreakBlockAction breakMaster = new BreakBlockAction(cropPos, null, FarmerUtility.HARVEST_BASE_TICKS,
                    FarmerUtility.HARVEST_EXHAUSTION, true, FarmCrops::isMatureCrop,
                    CivilianSkill.HARVESTING, CivilianSkills.HARVEST_CROP_BASE_XP, true);
            helper.assertTrue(runActionToCompletion(breakMaster, ctx, 40) == CivilianAction.Status.SUCCEEDED,
                    "Master harvest succeeded");
            int durMaster = breakMaster.durationTicks();
            double exhDeltaMaster = c.food().exhaustion() - exhBeforeMaster;

            helper.assertTrue(dur0 == 24 && durSimple == 12 && durMaster == 5,
                    "Harvest durations match 0.5x/1.0x/2.5x speed: " + dur0 + "/" + durSimple + "/" + durMaster);
            helper.assertTrue(exhDeltaMaster < exhDeltaSimple && Math.abs(exhDeltaMaster / exhDeltaSimple - (0.5 / 0.875)) < 0.05,
                    "Master exhaustion scales linearly down to 0.5x (simple=" + exhDeltaSimple + ", master=" + exhDeltaMaster + ")");
            helper.assertTrue(xpGainSimple > 0.0 && Math.abs(xpGainAdv / xpGainSimple - 0.5) < 0.02,
                    "Advanced (2x speed) gains 0.5x XP per action vs Simple (simple=" + xpGainSimple + ", adv=" + xpGainAdv + ")");

            // 2. Master Harvesting yields ~1.5x wheat produce across 50 harvests (never < 55, expected ~75).
            c.inventory().clearContent();
            c.skills().setLevel(CivilianSkill.HARVESTING, 1.0);
            int totalWheat = 0;
            for (int i = 0; i < 50; i++) {
                level.setBlock(cropPos, ripeWheat, Block.UPDATE_ALL);
                BreakBlockAction b = new BreakBlockAction(cropPos, null, 1, 0.0F, true,
                        FarmCrops::isMatureCrop, CivilianSkill.HARVESTING, 0.0, true);
                helper.assertTrue(runActionToCompletion(b, ctx, 5) == CivilianAction.Status.SUCCEEDED, "yield harvest ok");
                totalWheat += c.inventory().countItem(Items.WHEAT);
                c.inventory().clearContent();
            }
            helper.assertTrue(totalWheat > 55, "Master Harvesting averages 1.5x wheat produce over 50 crops, got " + totalWheat);

            // Verify FarmCrops.isCropProduceDrop distinguishes produce vs separate non-edible seeds.
            List<ItemStack> wheatDrops = List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT_SEEDS, 2));
            helper.assertTrue(FarmCrops.isCropProduceDrop(wheatDrops.get(0), wheatDrops), "wheat is produce");
            helper.assertFalse(FarmCrops.isCropProduceDrop(wheatDrops.get(1), wheatDrops), "wheat_seeds is not produce");
            List<ItemStack> potatoDrops = List.of(new ItemStack(Items.POTATO, 3), new ItemStack(Items.POISONOUS_POTATO));
            helper.assertTrue(FarmCrops.isCropProduceDrop(potatoDrops.get(0), potatoDrops), "potato is produce");
            helper.assertFalse(FarmCrops.isCropProduceDrop(potatoDrops.get(1), potatoDrops), "poisonous_potato excluded");

            // 3. Planting awards Cultivation XP; chest transfer awards Logistics XP.
            c.skills().setLevel(CivilianSkill.CULTIVATION, 0.25);
            c.skills().setLevel(CivilianSkill.LOGISTICS, 0.25);
            double cultBefore = c.skills().xp(CivilianSkill.CULTIVATION);
            c.inventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 4));
            PlaceBlockAction plant = new PlaceBlockAction(cropPos, s -> s.is(Items.WHEAT_SEEDS),
                    s -> Blocks.WHEAT.defaultBlockState(), FarmerUtility.PLANT_BASE_TICKS, FarmerUtility.PLANT_EXHAUSTION,
                    true, CivilianSkill.CULTIVATION, CivilianSkills.PLANT_CROP_BASE_XP);
            helper.assertTrue(runActionToCompletion(plant, ctx, 30) == CivilianAction.Status.SUCCEEDED, "plant succeeded");
            helper.assertTrue(c.skills().xp(CivilianSkill.CULTIVATION) > cultBefore, "planting awarded Cultivation XP");

            double logBefore = c.skills().xp(CivilianSkill.LOGISTICS);
            c.inventory().setItem(1, new ItemStack(Items.WHEAT, 32));
            TransferItemsAction deposit = new TransferItemsAction(chestPos, TransferItemsAction.Mode.DEPOSIT,
                    s -> s.is(Items.WHEAT) ? s.getCount() : 0, FarmerUtility.TRANSFER_BASE_TICKS);
            helper.assertTrue(runActionToCompletion(deposit, ctx, 30) == CivilianAction.Status.SUCCEEDED, "deposit succeeded");
            helper.assertTrue(c.skills().xp(CivilianSkill.LOGISTICS) > logBefore, "deposit awarded Logistics XP");
            helper.succeed();
        } finally {
            c.discard();
            clearDrops(level, main, 6.0);
            disband(helper, empire);
            clearBlocks(level, placed);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_skills_durability", timeoutTicks = 40)
    public static void masterCultivationReducesHoeDurabilityWear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos main = mainPos(helper);
        List<BlockPos> placed = new ArrayList<>();
        CivilianEntity c = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main), null);
        try {
            c.setTraits(CivilianTraits.NEUTRAL);
            CivilianRoster.recruit(c, empire, level);
            BlockPos soil = main.offset(1, -1, 0);
            placed.add(soil);
            AiContext ctx = aiContext(level, c, empire);

            // At 0.25 (Simple, 0 fumbles, 81.25% durability) vs 1.0 (Master, 25% durability) over 60 tills:
            c.skills().setLevel(CivilianSkill.CULTIVATION, 1.0);
            ItemStack masterHoe = new ItemStack(Items.IRON_HOE);
            c.inventory().setItem(0, masterHoe);
            for (int i = 0; i < 60; i++) {
                level.setBlock(soil, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                InteractBlockAction till = InteractBlockAction.till(soil);
                helper.assertTrue(runActionToCompletion(till, ctx, 30) == CivilianAction.Status.SUCCEEDED, "master till ok");
                helper.assertTrue(level.getBlockState(soil).is(Blocks.FARMLAND), "master till converted dirt");
            }
            ItemStack heldMaster = c.getMainHandItem();
            int masterDamage = heldMaster.getDamageValue();
            helper.assertTrue(masterDamage < 30,
                    "Master Cultivation (25% durability wear) over 60 tills should use <30 durability, used " + masterDamage);

            // Compare with Unskilled (0.0): every swing (even fumbles) uses 100% durability.
            c.skills().setLevel(CivilianSkill.CULTIVATION, 0.0);
            c.inventory().clearContent();
            c.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            ItemStack rookieHoe = new ItemStack(Items.IRON_HOE);
            c.inventory().setItem(0, rookieHoe);
            int rookieAttempts = 20;
            for (int i = 0; i < rookieAttempts; i++) {
                c.skills().setLevel(CivilianSkill.CULTIVATION, 0.0);
                level.setBlock(soil, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                InteractBlockAction till = InteractBlockAction.till(soil);
                helper.assertTrue(runActionToCompletion(till, ctx, 50) == CivilianAction.Status.SUCCEEDED, "rookie till ran");
            }
            int rookieDamage = c.getMainHandItem().getDamageValue();
            helper.assertTrue(rookieDamage == rookieAttempts,
                    "Unskilled Cultivation uses 100% durability per till, expected " + rookieAttempts + " got " + rookieDamage);
            helper.succeed();
        } finally {
            c.discard();
            disband(helper, empire);
            clearBlocks(level, placed);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_skills_fumbles", timeoutTicks = 60)
    public static void rookieFumblesDropItemsWithoutOverflowAndStopAtSimple(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        BlockPos main = mainPos(helper);
        List<BlockPos> placed = new ArrayList<>();
        CivilianEntity c = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main), null);
        try {
            // Very clumsy rookie -> hits MAX_FUMBLE_CHANCE (0.50) at skill 0.0.
            c.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.MOTOR_SENSORY, -100)
                    .with(BaseTrait.CONSCIENTIOUSNESS, -100)
                    .with(BaseTrait.NEUROTICISM, 100));
            c.skills().clearAll();
            CivilianRoster.recruit(c, empire, level);

            BlockPos soil = main.offset(1, -1, 0);
            BlockPos cropPos = soil.above();
            BlockPos chestPos = main.offset(-1, 0, 0);
            level.setBlock(soil, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), Block.UPDATE_ALL);
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            placed.add(soil);
            placed.add(cropPos);
            placed.add(chestPos);

            AiContext ctx = aiContext(level, c, empire);

            // 1. Harvest fumbles at 0.0: drops items on ground WITHOUT flagging droppedOverflow when pockets have space.
            int harvestFumbles = 0;
            for (int i = 0; i < 30; i++) {
                c.skills().setLevel(CivilianSkill.HARVESTING, 0.0);
                c.inventory().clearContent();
                level.setBlock(cropPos, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), Block.UPDATE_ALL);
                BreakBlockAction b = new BreakBlockAction(cropPos, null, 1, 0.0F, true,
                        FarmCrops::isMatureCrop, CivilianSkill.HARVESTING, 0.0, true);
                helper.assertTrue(runActionToCompletion(b, ctx, 10) == CivilianAction.Status.SUCCEEDED, "harvest ok");
                if (b.fumbled()) {
                    harvestFumbles++;
                    helper.assertFalse(b.droppedOverflow(), "rookie fumble must not trigger pocket-overflow flag");
                }
            }
            helper.assertTrue(harvestFumbles > 0, "clumsy 0.0 rookie fumbled at least once in 30 harvests");
            helper.assertFalse(level.getEntitiesOfClass(ItemEntity.class, new AABB(cropPos).inflate(3.0)).isEmpty(),
                    "harvest fumbles left visible item drops on the ground");
            clearDrops(level, main, 6.0);

            // 2. Planting fumbles at 0.0: drops seed on ground without planting crop, non-destructive.
            int seedFumbles = 0;
            for (int i = 0; i < 30; i++) {
                c.skills().setLevel(CivilianSkill.CULTIVATION, 0.0);
                c.inventory().clearContent();
                c.inventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 4));
                level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                PlaceBlockAction p = new PlaceBlockAction(cropPos, s -> s.is(Items.WHEAT_SEEDS),
                        s -> Blocks.WHEAT.defaultBlockState(), 1, 0.0F, true,
                        CivilianSkill.CULTIVATION, CivilianSkills.PLANT_CROP_BASE_XP);
                helper.assertTrue(runActionToCompletion(p, ctx, 10) == CivilianAction.Status.SUCCEEDED, "plant ran");
                if (p.fumbled()) {
                    seedFumbles++;
                    helper.assertTrue(level.getBlockState(cropPos).isAir(), "fumbled seed left plot unplanted");
                    helper.assertTrue(c.skills().xp(CivilianSkill.CULTIVATION) > 0.0, "fumbled seed still awarded attempt XP");
                }
            }
            helper.assertTrue(seedFumbles > 0, "clumsy 0.0 rookie fumbled at least once in 30 plantings");
            clearDrops(level, main, 6.0);

            // 3. Logistics transfer fumbles spill stackable produce, never hoes.
            int transferFumbles = 0;
            for (int i = 0; i < 30; i++) {
                c.skills().setLevel(CivilianSkill.LOGISTICS, 0.0);
                c.inventory().clearContent();
                c.inventory().setItem(0, new ItemStack(Items.POTATO, 8));
                TransferItemsAction dep = new TransferItemsAction(chestPos, TransferItemsAction.Mode.DEPOSIT,
                        s -> s.is(Items.POTATO) ? s.getCount() : 0, 1);
                helper.assertTrue(runActionToCompletion(dep, ctx, 10) == CivilianAction.Status.SUCCEEDED, "deposit ran");
                if (dep.fumbled()) {
                    transferFumbles++;
                }
                // Clear chest between iterations so it doesn't fill up.
                IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, chestPos, null);
                if (handler != null) {
                    for (int s = 0; s < handler.getSlots(); s++) {
                        handler.extractItem(s, 64, false);
                    }
                }
            }
            helper.assertTrue(transferFumbles > 0, "clumsy 0.0 rookie fumbled at least once in 30 chest transfers");
            clearDrops(level, main, 6.0);

            // 4. At Simple (0.25), ZERO fumbles occur even with terrible traits and freezing stress.
            c.skills().setAllLevels(CivilianSkills.ROOKIE_SKILL_CEILING);
            c.fulfilment().recordComfortSample(0.0F);
            c.setLastNight(0.0F);
            for (int i = 0; i < 30; i++) {
                c.inventory().clearContent();
                level.setBlock(cropPos, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), Block.UPDATE_ALL);
                BreakBlockAction b = new BreakBlockAction(cropPos, null, 1, 0.0F, true,
                        FarmCrops::isMatureCrop, CivilianSkill.HARVESTING, 0.0, true);
                runActionToCompletion(b, ctx, 10);
                helper.assertFalse(b.fumbled(), "Simple (0.25) worker must never fumble");
            }
            helper.succeed();
        } finally {
            c.discard();
            clearDrops(level, main, 6.0);
            disband(helper, empire);
            clearBlocks(level, placed);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "civilian_skills_decay", timeoutTicks = 40)
    public static void activeDayRolloverDecaysUnusedCitizenSkillsAfterGraceOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        EmpireSavedData data = EmpireSavedData.of(level);
        BlockPos main = mainPos(helper);
        CivilianEntity citizen = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main), null);
        CivilianEntity freeCiv = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main.east()), null);
        try {
            CivilianRecord record = CivilianRoster.recruit(citizen, empire, level);
            citizen.skills().clearAll();
            citizen.skills().setLevel(CivilianSkill.HARVESTING, 0.50); // 2,400 XP
            citizen.skills().setLevel(CivilianSkill.LOGISTICS, 0.50);  // 2,400 XP
            citizen.syncNeedsToRecord();

            freeCiv.skills().clearAll();
            freeCiv.skills().setLevel(CivilianSkill.EXCAVATION, 0.50);

            // Simulate 3 active days (grace period): Logistics stays intact.
            for (int day = 0; day < CivilianSkills.DECAY_GRACE_DAYS; day++) {
                // Practice Harvesting each day so it stays fresh.
                citizen.awardSkillActionXp(level, CivilianSkill.HARVESTING, 1.0);
                for (int t = 0; t < CivilianFood.DAY_LENGTH; t++) {
                    if (citizen.food().countActiveTick()) {
                        citizen.skills().advanceDay();
                    }
                    if (freeCiv.food().countActiveTick()) {
                        // FREE civilians do not trigger decay in aiStep; verify our gate directly.
                        if (freeCiv.status() == CivilianEntity.Status.CITIZEN) {
                            freeCiv.skills().advanceDay();
                        }
                    }
                }
            }
            helper.assertTrue(Math.abs(citizen.skills().xp(CivilianSkill.LOGISTICS) - 2_400.0) < 1e-6,
                    "Logistics preserved throughout 3-day grace period");
            helper.assertTrue(citizen.skills().inactiveDays(CivilianSkill.LOGISTICS) == CivilianSkills.DECAY_GRACE_DAYS,
                    "Logistics tracked 3 inactive days");

            // 4th active day triggers 20 XP decay on Logistics; Harvesting was practiced so it does not decay.
            double harvestBeforeDay4 = citizen.skills().xp(CivilianSkill.HARVESTING);
            citizen.awardSkillActionXp(level, CivilianSkill.HARVESTING, 1.0);
            double harvestAfterPractice = citizen.skills().xp(CivilianSkill.HARVESTING);
            for (int t = 0; t < CivilianFood.DAY_LENGTH; t++) {
                if (citizen.food().countActiveTick() && citizen.skills().advanceDay()) {
                    citizen.syncNeedsToRecord();
                }
            }
            helper.assertTrue(Math.abs(citizen.skills().xp(CivilianSkill.LOGISTICS) - (2_400.0 - CivilianSkills.DAILY_DECAY_XP)) < 1e-6,
                    "Logistics lost 20 XP on day 4, got " + citizen.skills().xp(CivilianSkill.LOGISTICS));
            helper.assertTrue(Math.abs(record.skills().entry(CivilianSkill.LOGISTICS).xp() - (2_400.0 - CivilianSkills.DAILY_DECAY_XP)) < 1e-6,
                    "decayed Logistics XP synced to CivilianRecord");
            helper.assertTrue(citizen.skills().xp(CivilianSkill.HARVESTING) >= harvestAfterPractice
                            && harvestAfterPractice > harvestBeforeDay4,
                    "practiced Harvesting did not decay");
            helper.assertTrue(Math.abs(freeCiv.skills().xp(CivilianSkill.EXCAVATION) - 2_400.0) < 1e-6,
                    "non-citizen skill did not decay");
            helper.succeed();
        } finally {
            citizen.discard();
            freeCiv.discard();
            disband(helper, empire);
        }
    }

    private CivilianSkillsGameTests() {
    }
}
