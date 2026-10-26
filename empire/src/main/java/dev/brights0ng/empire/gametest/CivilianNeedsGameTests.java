// Written by ChatGPT; empire setup and cleanup adapted from Claude's CivilianRosterGameTests.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.civilian.TravellerArrivals;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.CivilianFoodNbt;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianAbilities;
import dev.brights0ng.empire.rules.CivilianFood;
import dev.brights0ng.empire.rules.TravellerWants;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianNeedsGameTests {
    private static final String BATCH = "civilians";
    private static final String KNOWN_CODE = "12-9-11-10-14-8";

    private static BlockPos mainPos(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(1, 1, 1));
    }

    private static EmpireRecord found(GameTestHelper helper, Player founder) {
        BlockPos main = mainPos(helper);
        return EmpireSavedData.of(helper.getLevel()).createEmpire(founder.getUUID(),
                ChunkKeys.pack(main.getX() >> 4, main.getZ() >> 4), main, helper.getLevel().getGameTime());
    }

    private static void disband(GameTestHelper helper, EmpireRecord empire) {
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        data.disbandEmpire(empire.id(), null);
        data.onBonfireRemoved(mainPos(helper), null);
    }

    /** Keep needs tests independent of navigation, falling and nearby fixtures. */
    private static CivilianEntity needsBody(GameTestHelper helper, int food, float saturation, float health) {
        CivilianEntity civilian = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(mainPos(helper)), null);
        civilian.setNoAi(true);
        civilian.setNoGravity(true);
        civilian.setFood(food, saturation);
        civilian.setHealth(health);
        return civilian;
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 380)
    public static void starvationStopsAtHalfHealth(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 0, 0, 16.5F);
        helper.runAtTickTime(165, () -> {
            helper.assertTrue(civilian.getMaxHealth() == 30.0F, "maximum health stays 30");
            helper.assertTrue(civilian.getHealth() == 15.0F, "starvation clips the last half point to exactly 15");
        });
        helper.runAtTickTime(365, () -> {
            try {
                helper.assertTrue(civilian.getHealth() == 15.0F && civilian.isAlive(), "alive at 15 after another 200 ticks");
            } finally {
                civilian.discard();
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 90)
    public static void slowRegenHeals(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 18, 0, 20);
        helper.runAtTickTime(85, () -> {
            try {
                helper.assertTrue(civilian.getHealth() == 21.0F, "slow regeneration heals one HP within 85 ticks");
            } finally {
                civilian.discard();
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void fastRegenHeals(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 20, 5, 20);
        helper.runAtTickTime(12, () -> {
            try {
                helper.assertTrue(civilian.getHealth() > 20.0F, "saturated regeneration heals within 12 ticks");
                helper.assertTrue(civilian.food().saturation() < 5.0F, "healing spends saturation through exhaustion");
            } finally {
                civilian.discard();
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void naturalRegenerationOff(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 20, 5, 20);
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION);
        boolean previous = rule.get();
        try {
            rule.set(false, helper.getLevel().getServer());
            // The gamerule is global: run body ticks synchronously so concurrent tests cannot observe it disabled.
            for (int i = 0; i < 200; i++) civilian.aiStep();
            helper.assertTrue(civilian.getHealth() == 20.0F, "no healing with natural regeneration off for 200 body ticks");
            helper.assertTrue(civilian.food().activeTicks() == 200, "all 200 needs ticks ran");
        } finally {
            rule.set(previous, helper.getLevel().getServer());
            civilian.discard();
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH, timeoutTicks = 1120)
    public static void basalDrainLowersFood(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 20, 0, 30);
        helper.assertTrue(EmpireConfig.civilianBasalExhaustionPerTick() == 0.0037, "default basal exhaustion is configured");
        helper.runAtTickTime(1100, () -> {
            try {
                helper.assertTrue(civilian.food().foodLevel() == 19, "1100 ticks of basal drain lower food to 19");
                helper.assertTrue(civilian.food().activeTicks() >= 1099, "body really ticked for the interval");
            } finally {
                civilian.discard();
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void respawnKeepsFoodAndHealth(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = needsBody(helper, 7, 0, 18);
        CivilianEntity replacement = null;
        try {
            CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
            helper.assertTrue(record.food().equals(civilian.food().save()) && record.health() == 18, "recruit copies needs");
            // Alter after recruitment to prove removal flushes the most recent state too.
            civilian.food().addExhaustion(1.25F);
            civilian.food().eat(0, 1.0F);
            UUID oldBody = civilian.getUUID();
            civilian.discard();
            helper.assertTrue(record.food().equals(civilian.food().save()), "discard writes the last needs snapshot");
            CivilianRoster.runMissingCheck(helper.getLevel());
            helper.assertTrue(record.body().equals(oldBody), "first missing check keeps the old body");
            CivilianRoster.runMissingCheck(helper.getLevel());
            helper.assertFalse(record.body().equals(oldBody), "second missing check replaces the body");
            replacement = (CivilianEntity) helper.getLevel().getEntity(record.body());
            helper.assertTrue(replacement != null, "replacement exists");
            helper.assertTrue(replacement.food().save().equals(record.food()), "replacement keeps the complete food snapshot");
            helper.assertTrue(replacement.food().foodLevel() == 7 && replacement.getHealth() == 18, "replacement is not healed or fed");
        } finally {
            civilian.discard();
            if (replacement != null) replacement.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void needsPersistenceAndTolerantLoads(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 7, 2, 18);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        try {
            CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
            civilian.food().addExhaustion(2.5F);
            civilian.food().eat(1, 0);
            civilian.food().countActiveTick();
            civilian.setHealth(17.5F);
            CompoundTag bodyTag = civilian.saveWithoutId(new CompoundTag());
            helper.assertTrue(record.food().equals(civilian.food().save()) && record.health() == 17.5F, "entity save writes through");
            CivilianRecord loadedRecord = CivilianRecord.load(record.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            helper.assertTrue(loadedRecord.food().equals(record.food()) && loadedRecord.health() == record.health(), "record needs round trip");
            CivilianEntity loaded = EmpireContent.CIVILIAN.get().create(helper.getLevel());
            loaded.load(bodyTag);
            helper.assertTrue(loaded.food().save().equals(civilian.food().save()), "body food round trip");
            civilian.setFood(6, 20);
            helper.assertTrue(record.food().foodLevel() == 6 && record.food().saturation() == 6, "debug edit clamps and writes through");
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
            data.setDirty(false);
            civilian.syncNeedsToRecord();
            helper.assertFalse(data.isDirty(), "unchanged needs do not dirty saved data");
            loaded.setUUID(UUID.randomUUID());
            loaded.setFood(1, 0);
            loaded.syncNeedsToRecord();
            helper.assertTrue(record.food().foodLevel() == 6 && !data.isDirty(), "stale body cannot write to the record");
            CompoundTag legacy = record.save(helper.getLevel().registryAccess());
            legacy.remove("food");
            legacy.remove("health");
            CivilianRecord old = CivilianRecord.load(legacy, helper.getLevel().registryAccess());
            helper.assertTrue(old.food().equals(new CivilianFood().save()) && Float.isNaN(old.health()), "missing record needs use defaults and NaN");
            bodyTag.remove("Food");
            loaded.load(bodyTag);
            helper.assertTrue(loaded.food().save().equals(new CivilianFood().save()), "missing body food defaults");
            CompoundTag corrupt = new CompoundTag();
            corrupt.putInt("food_level", 99);
            corrupt.putFloat("saturation", Float.NaN);
            corrupt.putFloat("exhaustion", -3);
            helper.assertTrue(CivilianFoodNbt.load(corrupt).foodLevel() == 20
                    && CivilianFoodNbt.load(corrupt).saturation() == 5
                    && CivilianFoodNbt.load(corrupt).exhaustion() == 0, "corrupt food fields repair independently");
        } finally {
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void needsSyncEveryHundredTicks(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 7, 0, 18);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
        try {
            for (int tick = 1; tick <= 100; tick++) {
                helper.getLevel().tickNonPassenger(civilian);
                if (tick < 100) helper.assertTrue(record.food().activeTicks() == 0, "no early periodic write");
            }
            helper.assertTrue(record.food().activeTicks() == 100, "100-tick snapshot written to record");
            helper.assertTrue(record.health() == 18.0F, "record keeps health");
        } finally {
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void damageJumpAndLeavingExhaustion(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 7, 0, 30);
        try {
            civilian.jumpFromGround();
            helper.assertTrue(Math.abs(civilian.food().exhaustion() - 0.05F) < 0.00001F, "ordinary jump adds 0.05 exhaustion");
            civilian.setDeltaMovement(Vec3.ZERO);
            float before = civilian.food().exhaustion();
            civilian.hurt(civilian.damageSources().generic(), 2.0F);
            helper.assertTrue(civilian.getHealth() == 28.0F, "damage reaches health");
            helper.assertTrue(Math.abs(civilian.food().exhaustion() - before
                    - civilian.damageSources().generic().getFoodExhaustion()) < 0.00001F, "damage adds source exhaustion");
            civilian.startLeaving();
            CivilianFood.Saved frozen = civilian.food().save();
            civilian.jumpFromGround();
            civilian.aiStep();
            helper.assertTrue(civilian.food().save().equals(frozen), "leaving body has no active hunger or exhaustion");
        } finally {
            civilian.discard();
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void waterMovementAddsVanillaExhaustion(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.WATER);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.WATER);
        CivilianEntity civilian = needsBody(helper, 7, 0, 30);
        try {
            civilian.setNoAi(false); // Mob travel is disabled with NoAI; this test needs real movement.
            civilian.setDeltaMovement(new Vec3(0.2, 0.1, 0.1));
            Vec3 before = civilian.position();
            helper.getLevel().tickNonPassenger(civilian);
            Vec3 delta = civilian.position().subtract(before);
            helper.assertTrue(civilian.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), "body is submerged");
            int centimetres = Math.round((float) delta.length() * 100.0F);
            helper.assertTrue(centimetres > 0, "body moved in water");
            float expected = (float) EmpireConfig.civilianBasalExhaustionPerTick() + 0.01F * centimetres * 0.01F;
            helper.assertTrue(Math.abs(civilian.food().exhaustion() - expected) < 0.00001F, "water uses rounded three-axis metres");
        } finally {
            civilian.discard();
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void joinRestoresNeedsAndClampsHealth(GameTestHelper helper) {
        CivilianEntity civilian = needsBody(helper, 7, 1, 18);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity loaded = null;
        try {
            CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
            CompoundTag saved = civilian.saveWithoutId(new CompoundTag());
            civilian.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            for (float health : new float[]{Float.NaN, -4.0F, 50.0F}) {
                record.setHealth(health);
                loaded = EmpireContent.CIVILIAN.get().create(helper.getLevel());
                loaded.load(saved);
                // The incoming body's stale food and health must yield to the record on join.
                loaded.food().eat(10, 5);
                loaded.setHealth(12.0F);
                helper.assertTrue(helper.getLevel().addFreshEntity(loaded), "current body rejoins");
                helper.assertTrue(loaded.food().save().equals(record.food()), "record food wins on join");
                float expected = Float.isNaN(health) ? 12.0F : Math.clamp(health, 1.0F, 30.0F);
                helper.assertTrue(loaded.getHealth() == expected, "join keeps NaN health or clamps to [1, max]");
                loaded.food().addExhaustion(0.5F);
                loaded.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                helper.assertTrue(record.food().equals(loaded.food().save()), "unload flushes needs");
                helper.assertTrue(record.health() == expected, "unload flushes health");
            }
        } finally {
            civilian.discard();
            if (loaded != null) loaded.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void recruitCopiesAbilities(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity traveller = TravellerArrivals.spawnTraveller(empire, helper.getLevel(), mainPos(helper).east());
        try {
            CivilianAbilities rolled = traveller.abilities();
            CivilianRecord record = CivilianRoster.recruit(traveller, empire, helper.getLevel());
            helper.assertTrue(rolled.pack() == record.abilities(), "recruit copies traveller scores");
            traveller.setAbilities(CivilianAbilities.parse(KNOWN_CODE));
            helper.assertTrue(record.abilities() == traveller.abilities().pack(), "setter writes through");
            helper.assertTrue(CivilianRecord.load(record.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess()).abilities() == record.abilities(), "record NBT round trip");
        } finally {
            traveller.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void respawnKeepsAbilities(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(mainPos(helper).east()), null);
        CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
        CivilianEntity replacement = null;
        try {
            civilian.setAbilities(CivilianAbilities.parse(KNOWN_CODE));
            UUID oldBody = civilian.getUUID();
            civilian.discard();
            CivilianRoster.runMissingCheck(helper.getLevel());
            helper.assertTrue(record.body().equals(oldBody), "first check keeps the missing body");
            CivilianRoster.runMissingCheck(helper.getLevel());
            helper.assertFalse(record.body().equals(oldBody), "second check replaces the body");
            helper.assertTrue(helper.getLevel().getEntity(record.body()) instanceof CivilianEntity, "replacement exists");
            replacement = (CivilianEntity) helper.getLevel().getEntity(record.body());
            helper.assertTrue(KNOWN_CODE.equals(replacement.abilities().code()), "replacement keeps scores");
        } finally {
            civilian.discard();
            if (replacement != null) replacement.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void v1RecordGetsStableAbilities(GameTestHelper helper) {
        UUID id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        CompoundTag tag = new CivilianRecord(id, UUID.randomUUID(), UUID.randomUUID(), "Legacy", 0,
                BlockPos.ZERO, 0, 0).save(helper.getLevel().registryAccess());
        tag.putInt("version", 1);
        tag.remove("abilities");
        CivilianRecord first = CivilianRecord.load(tag, helper.getLevel().registryAccess());
        CivilianRecord second = CivilianRecord.load(tag, helper.getLevel().registryAccess());
        helper.assertTrue(first.abilities() == second.abilities(), "legacy migration is stable");
        int expected = CivilianAbilities.roll(new java.util.Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits())).pack();
        helper.assertTrue(first.abilities() == expected, "legacy migration uses the civilian id seed");
        tag.putInt("abilities", -1);
        helper.assertTrue(CivilianRecord.load(tag, helper.getLevel().registryAccess()).abilities() == expected, "corrupt scores use the stable default");
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void entitySavesAbilitiesAndRepairsMissingOrCorrupt(GameTestHelper helper) {
        CivilianEntity civilian = EmpireContent.CIVILIAN.get().create(helper.getLevel());
        civilian.setAbilities(CivilianAbilities.parse(KNOWN_CODE));
        CompoundTag tag = civilian.saveWithoutId(new CompoundTag());
        CivilianEntity loaded = EmpireContent.CIVILIAN.get().create(helper.getLevel());
        loaded.load(tag);
        helper.assertTrue(KNOWN_CODE.equals(loaded.abilities().code()), "entity NBT keeps scores");
        for (boolean corrupt : new boolean[]{false, true}) {
            tag.remove("Abilities");
            if (corrupt) tag.putInt("Abilities", -1);
            loaded.load(tag);
            helper.assertTrue(loaded.abilities().mean() >= 8 && loaded.abilities().mean() <= 13, "missing/corrupt scores rerolled");
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void sneakInspectsWithoutRecruitingOrDialogue(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, player);
        CivilianEntity traveller = TravellerArrivals.spawnTraveller(empire, helper.getLevel(), mainPos(helper).east());
        try {
            player.setShiftKeyDown(true);
            helper.assertTrue(player.isSecondaryUseActive(), "mock player can sneak");
            TravellerWants.Want want = TravellerWants.WANTS.get(traveller.want());
            ItemStack food = new ItemStack(CivilianRoster.item(want), want.count());
            player.setItemInHand(InteractionHand.MAIN_HAND, food);
            helper.assertTrue(traveller.interact(player, InteractionHand.MAIN_HAND).consumesAction(), "inspection succeeds");
            helper.assertTrue(food.getCount() == want.count(), "inspection consumes no food");
            helper.assertTrue(traveller.status() == CivilianEntity.Status.TRAVELLER, "inspection does not recruit");
            helper.assertTrue(traveller.dialoguePlayer() == null, "inspection starts no dialogue");
            String name = traveller.getName().getString();
            ItemStack tag = new ItemStack(Items.NAME_TAG);
            tag.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Rename"));
            player.setItemInHand(InteractionHand.MAIN_HAND, tag);
            player.interactOn(traveller, InteractionHand.MAIN_HAND);
            helper.assertTrue(tag.getCount() == 1 && name.equals(traveller.getName().getString()), "name tag inspection preserves item and name");
            helper.assertTrue(traveller.dialoguePlayer() == null, "name tag inspection starts no dialogue");
        } finally {
            traveller.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void handOverFillsInventory(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = needsBody(helper, 20, 5, 30);
        try {
            CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 16));
            helper.assertTrue(civilian.interact(player, InteractionHand.MAIN_HAND).consumesAction(), "hand-over succeeds for a non-member");
            helper.assertTrue(civilian.inventory().countItem(Items.BREAD) == 16, "pockets contain 16 bread");
            helper.assertTrue(player.getMainHandItem().isEmpty(), "survival hand is empty");
            helper.assertTrue(record.inventory().getFirst().getCount() == 16, "hand-over immediately writes through");
            helper.assertTrue(civilian.dialoguePlayer() == null, "hand-over starts no dialogue");
        } finally {
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void freeCivilianTakesNoFood(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CivilianEntity civilian = needsBody(helper, 20, 5, 30);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 16));
            civilian.interact(player, InteractionHand.MAIN_HAND);
            helper.assertTrue(civilian.inventory().isEmpty(), "free civilian takes no food");
            helper.assertTrue(player.getMainHandItem().getCount() == 16, "held food unchanged");
        } finally {
            civilian.discard();
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void handOverStopsWhenFull(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, player);
        CivilianEntity civilian = needsBody(helper, 20, 5, 30);
        try {
            CivilianRoster.recruit(civilian, empire, helper.getLevel());
            for (int slot = 0; slot < CivilianEntity.INVENTORY_SIZE; slot++) {
                civilian.inventory().setItem(slot, new ItemStack(Items.COOKED_COD, 64));
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 16));
            helper.assertTrue(civilian.interact(player, InteractionHand.MAIN_HAND).consumesAction(), "full pockets handle click");
            helper.assertTrue(civilian.inventory().countItem(Items.COOKED_COD) == 9 * 64
                    && civilian.inventory().countItem(Items.BREAD) == 0, "all nine slots unchanged");
            helper.assertTrue(player.getMainHandItem().getCount() == 16, "full pockets consume no bread");
            helper.assertTrue(civilian.dialoguePlayer() == null, "full pockets start no dialogue");
        } finally {
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void sneakTakesNoFood(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, player);
        CivilianEntity civilian = needsBody(helper, 20, 5, 30);
        try {
            CivilianRoster.recruit(civilian, empire, helper.getLevel());
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 16));
            civilian.interact(player, InteractionHand.MAIN_HAND);
            helper.assertTrue(civilian.inventory().isEmpty(), "sneak inspection takes no bread");
            helper.assertTrue(player.getMainHandItem().getCount() == 16, "sneak keeps held bread");
            helper.assertTrue(civilian.dialoguePlayer() == null, "sneak starts no dialogue");
        } finally {
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void respawnKeepsInventory(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = needsBody(helper, 20, 5, 30);
        CivilianEntity replacement = null;
        try {
            civilian.inventory().setItem(8, new ItemStack(Items.BREAD, 2));
            CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
            civilian.inventory().getItem(8).grow(1);
            helper.assertTrue(record.inventory().get(8).getCount() == 2, "recruit copied stacks independently");
            UUID oldBody = civilian.getUUID();
            civilian.discard();
            CivilianRoster.runMissingCheck(helper.getLevel());
            helper.assertTrue(record.body().equals(oldBody), "first check keeps old body");
            CivilianRoster.runMissingCheck(helper.getLevel());
            replacement = (CivilianEntity) helper.getLevel().getEntity(record.body());
            helper.assertTrue(replacement != null && !record.body().equals(oldBody), "second check creates new body");
            helper.assertTrue(replacement.inventory().getItem(8).is(Items.BREAD)
                    && replacement.inventory().countItem(Items.BREAD) == 3, "replacement keeps three bread and slot");
            replacement.inventory().getItem(8).shrink(1);
            helper.assertTrue(record.inventory().get(8).getCount() == 3, "replacement also has independent stacks");
        } finally {
            civilian.discard();
            if (replacement != null) replacement.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void deathDropsInventory(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = needsBody(helper, 20, 5, 30);
        java.util.List<ItemEntity> drops = java.util.List.of();
        try {
            CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
            civilian.inventory().setItem(8, new ItemStack(Items.BREAD, 4));
            civilian.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 2));
            civilian.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.CARROT, 3));
            civilian.hurt(civilian.damageSources().genericKill(), Float.MAX_VALUE);
            drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, civilian.getBoundingBox().inflate(3));
            helper.assertTrue(droppedCount(drops, Items.BREAD) == 4, "four bread dropped within three blocks");
            helper.assertTrue(droppedCount(drops, Items.APPLE) == 2 && droppedCount(drops, Items.CARROT) == 3,
                    "both hands drop exactly once without player kill or equipment chance");
            helper.assertTrue(civilian.inventory().isEmpty() && civilian.getMainHandItem().isEmpty()
                    && civilian.getOffhandItem().isEmpty(), "carried stacks removed from dead body");
            helper.assertTrue(EmpireSavedData.of(helper.getLevel()).civilian(record.id()) == null, "death deletes record");
        } finally {
            drops.forEach(Entity::discard);
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    private static int droppedCount(java.util.List<ItemEntity> drops, net.minecraft.world.item.Item item) {
        return drops.stream().map(ItemEntity::getItem).filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void partialAndCreativeHandOver(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, player);
        CivilianEntity civilian = needsBody(helper, 20, 5, 30);
        try {
            CivilianRoster.recruit(civilian, empire, helper.getLevel());
            for (int slot = 0; slot < 8; slot++) civilian.inventory().setItem(slot, new ItemStack(Items.COOKED_COD, 64));
            civilian.inventory().setItem(8, new ItemStack(Items.BREAD, 60));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 16));
            civilian.interact(player, InteractionHand.MAIN_HAND);
            helper.assertTrue(civilian.inventory().countItem(Items.BREAD) == 64
                    && player.getMainHandItem().getCount() == 12, "only four bread fit and are consumed");
            Player creative = helper.makeMockPlayer(GameType.CREATIVE);
            // makeMockPlayer overrides isCreative but does not initialize the game mode's abilities.
            GameType.CREATIVE.updatePlayerAbilities(creative.getAbilities());
            civilian.inventory().setItem(8, ItemStack.EMPTY);
            creative.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 16));
            civilian.interact(creative, InteractionHand.MAIN_HAND);
            helper.assertTrue(civilian.inventory().countItem(Items.BREAD) == 16
                    && creative.getMainHandItem().getCount() == 16, "creative gives a copy without consuming");
            Player spectator = helper.makeMockPlayer(GameType.SPECTATOR);
            spectator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 16));
            civilian.interact(spectator, InteractionHand.MAIN_HAND);
            helper.assertTrue(civilian.inventory().countItem(Items.BREAD) == 16
                    && spectator.getMainHandItem().getCount() == 16, "spectator cannot give food");
        } finally {
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void inventoryPersistenceAndJoin(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = needsBody(helper, 20, 5, 30);
        CivilianEntity loaded = null;
        try {
            civilian.inventory().setItem(8, new ItemStack(Items.BREAD, 3));
            CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
            CompoundTag bodyTag = civilian.saveWithoutId(new CompoundTag());
            CompoundTag recordTag = record.save(helper.getLevel().registryAccess());
            CivilianRecord restored = CivilianRecord.load(recordTag, helper.getLevel().registryAccess());
            helper.assertTrue(restored.inventory().get(8).is(Items.BREAD)
                    && restored.inventory().get(8).getCount() == 3, "record round trip preserves slot and stack");
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
            EmpireSavedData restoredData = EmpireSavedData.load(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                    helper.getLevel().registryAccess());
            helper.assertTrue(restoredData.civilian(record.id()).inventory().get(8).getCount() == 3, "saved data passes registries");
            data.setDirty(false);
            civilian.syncNeedsToRecord();
            helper.assertFalse(data.isDirty(), "unchanged inventory does not dirty data");
            civilian.inventory().getItem(8).grow(1);
            civilian.syncNeedsToRecord();
            helper.assertTrue(data.isDirty() && record.inventory().get(8).getCount() == 4, "stack count mutation writes through");
            civilian.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            loaded = EmpireContent.CIVILIAN.get().create(helper.getLevel());
            loaded.load(bodyTag);
            helper.assertTrue(loaded.inventory().getItem(8).getCount() == 3, "body round trip keeps slots");
            helper.assertTrue(helper.getLevel().addFreshEntity(loaded), "body rejoins");
            helper.assertTrue(loaded.inventory().getItem(8).getCount() == 4, "master inventory wins on join");
            recordTag.remove("inventory");
            helper.assertTrue(CivilianRecord.load(recordTag, helper.getLevel().registryAccess()).inventory().stream()
                    .allMatch(ItemStack::isEmpty), "legacy record has empty inventory");
            // Unjoined body: loading a missing or corrupt inventory must clear any previous contents.
            CivilianEntity legacyBody = EmpireContent.CIVILIAN.get().create(helper.getLevel());
            legacyBody.load(bodyTag);
            bodyTag.putString("Inventory", "corrupt");
            legacyBody.load(bodyTag);
            helper.assertTrue(legacyBody.inventory().isEmpty(), "wrong inventory NBT type defaults empty");
        } finally {
            civilian.discard();
            if (loaded != null) loaded.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    private CivilianNeedsGameTests() {
    }
}
