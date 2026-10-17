package dev.brights0ng.simplespoilage.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.simplespoilage.Cohort;
import dev.brights0ng.simplespoilage.FoodClimateSweep;
import dev.brights0ng.simplespoilage.FoodFreshness;
import dev.brights0ng.simplespoilage.FoodTemperature;
import dev.brights0ng.simplespoilage.SimpleSpoilage;
import dev.brights0ng.simplespoilage.SpoilClock;
import dev.brights0ng.simplespoilage.SpoilTimes;
import dev.brights0ng.simplespoilage.Spoilage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Food by temperature (2026-10-15; {@code claude/food-temperature.md}): the sweep applies the temperature where food is
 * to it. The temperature is set for each test ({@link FoodTemperature#setOverride}); all in one batch of their own.
 */
@GameTestHolder(SimpleSpoilage.MODID)
@PrefixGameTestTemplate(false)
public final class FoodTemperatureGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final String BATCH = "food_temperature";
    private static final int PERIOD = 1200;

    private static ItemStack bread(int count, long age) {
        ItemStack stack = new ItemStack(Items.BREAD, count);
        Spoilage.write(stack, FoodFreshness.born(SpoilClock.now() - age, count));
        return stack;
    }

    private static BarrelBlockEntity barrel(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, Blocks.BARREL);
        return (BarrelBlockEntity) helper.getBlockEntity(pos);
    }

    private static Cohort first(ItemStack stack) {
        return Spoilage.stored(stack).cohorts().get(0);
    }

    private static long halfStage() {
        SpoilTimes t = Spoilage.times();
        return (t.freshTicks() + t.ripeTicks() + t.staleTicks()) / 6;
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void aCellarHalvesTheSpeedAndAHotRoomDoublesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            BarrelBlockEntity barrel = barrel(helper);
            long now = SpoilClock.now();
            barrel.setItem(0, bread(4, SpoilTimes.DAY));
            long born = first(barrel.getItem(0)).born();
            FoodTemperature.setOverride(10, 10);
            FoodClimateSweep.blockEntity(level, barrel, now, PERIOD);
            helper.assertTrue(first(barrel.getItem(0)).born() == born, "the first visit only marks it");
            FoodClimateSweep.blockEntity(level, barrel, now + PERIOD, PERIOD);
            long cool = first(barrel.getItem(0)).born() - born;
            helper.assertTrue(cool == PERIOD / 2, "a minute at 10 C ages it half a minute (born moves 600 later): " + cool);
            FoodClimateSweep.blockEntity(level, barrel, now + PERIOD, PERIOD);
            helper.assertTrue(first(barrel.getItem(0)).born() - born == cool, "a second visit in the same tick does nothing");

            FoodTemperature.setOverride(30, 30);
            FoodClimateSweep.blockEntity(level, barrel, now + 2 * PERIOD, PERIOD);
            long hot = first(barrel.getItem(0)).born() - born - cool;
            helper.assertTrue(hot == -PERIOD, "a minute at 30 C ages it two (born moves 1200 earlier): " + hot);
        } finally {
            FoodTemperature.clearOverride();
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void aLongGapCatchesUpAtTheSeasonalTemperature(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            BarrelBlockEntity barrel = barrel(helper);
            long now = SpoilClock.now();
            barrel.setItem(0, bread(1, 10 * SpoilTimes.DAY));
            long born = first(barrel.getItem(0)).born();
            // Now 20 C, but the seasonal mean 10 C: a gap longer than three sweeps (an unloaded chunk) takes the mean
            FoodTemperature.setOverride(20, 10);
            FoodClimateSweep.blockEntity(level, barrel, now, PERIOD);
            long gap = 10L * PERIOD;
            FoodClimateSweep.blockEntity(level, barrel, now + gap, PERIOD);
            long moved = first(barrel.getItem(0)).born() - born;
            helper.assertTrue(moved == gap / 2, "the gap at the seasonal 10 C: " + moved);
        } finally {
            FoodTemperature.clearOverride();
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void foodOnTheGroundFreezesAndThawsWithAMargin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            ItemEntity item = (ItemEntity) helper.spawnItem(Items.BREAD, new net.minecraft.world.phys.Vec3(1.5, 2, 1.5));
            item.setItem(bread(1, 10 * SpoilTimes.DAY));
            item.setNoPickUpDelay();
            long now = SpoilClock.now();
            FoodTemperature.setOverride(-10, -10);
            // First visit marks it and freezes it
            sweepItem(level, item, now);
            helper.assertTrue(first(item.getItem()).frozen(), "frozen at -10 C");
            long born = first(item.getItem()).born();
            FoodTemperature.setOverride(1, 1);
            sweepItem(level, item, now + PERIOD);
            helper.assertTrue(first(item.getItem()).frozen(), "still frozen at +1 C (the margin)");
            FoodTemperature.setOverride(10, 10);
            sweepItem(level, item, now + 2 * PERIOD);
            Cohort thawed = first(item.getItem());
            helper.assertFalse(thawed.frozen(), "thawed at +10 C");
            long expected = born + Math.round(0.95 * PERIOD) - halfStage() + PERIOD / 2;
            helper.assertTrue(thawed.born() == expected, "a minute frozen, then the thaw, then a minute at 10 C: "
                    + (thawed.born() - born) + " vs " + (expected - born));
        } finally {
            FoodTemperature.clearOverride();
        }
        helper.succeed();
    }

    private static void sweepItem(ServerLevel level, ItemEntity item, long now) {
        // Items on the ground are swept with the rest of the level once a cycle; the same code, called directly here
        ItemStack stack = item.getItem();
        Spoilage.applyClimate(stack, now, FoodTemperature.at(level, item),
                () -> FoodTemperature.seasonal(level, item.blockPosition()), FoodClimateSweep.catchUpTicks(PERIOD), false);
        item.setItem(stack);
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void aPlayersFoodFollowsTheAirAroundThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "eae-food-2"));
            player.getInventory().clearContent();
            player.getInventory().setItem(0, bread(2, SpoilTimes.DAY));
            long born = first(player.getInventory().getItem(0)).born();
            long now = SpoilClock.now();
            FoodTemperature.setOverride(0, 0);
            FoodClimateSweep.player(level, player, now, PERIOD);
            FoodClimateSweep.player(level, player, now + PERIOD, PERIOD);
            long moved = first(player.getInventory().getItem(0)).born() - born;
            helper.assertTrue(moved == PERIOD - PERIOD / 4, "a minute at 0 C (not frozen: not below -2) ages it a quarter: " + moved);
            player.getInventory().clearContent();
        } finally {
            FoodTemperature.clearOverride();
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void foodMarkedAtDifferentTimesStillStacks(GameTestHelper helper) {
        ItemStack a = bread(1, SpoilTimes.DAY);
        ItemStack b = bread(1, SpoilTimes.DAY);
        Spoilage.markClimate(a, 100);
        Spoilage.markClimate(b, 200);
        helper.assertTrue(ItemStack.isSameItemSameComponents(a, b), "marks don't keep food apart");
        helper.succeed();
    }

    private FoodTemperatureGameTests() {
    }
}
