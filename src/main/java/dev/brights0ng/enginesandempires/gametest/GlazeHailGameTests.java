package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.surface.GlazeBlock;
import dev.brights0ng.enginesandempires.weather.surface.HailDamage;
import dev.brights0ng.enginesandempires.weather.surface.SurfaceContent;
import dev.brights0ng.enginesandempires.weather.surface.SurfaceWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Phase 5c of the weather backbone: glaze (freezing rain's ice layer), hail on players and mobs, and hail on crops. */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class GlazeHailGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    private static SurfaceWeather.Column freezingRain() {
        return new SurfaceWeather.Column(-3, Precip.FREEZING_RAIN, 1, false, 0, 0);
    }

    private static SurfaceWeather.Column snow() {
        return new SurfaceWeather.Column(-3, Precip.SNOW, 1, false, 0, 0);
    }

    private static SurfaceWeather.Column dry(double t) {
        return new SurfaceWeather.Column(t, null, 0, false, 0, 0);
    }

    private static SurfaceWeather.Column hail() {
        return new SurfaceWeather.Column(10, Precip.HAIL, 1, false, 0, 0);
    }

    private static void visit(GameTestHelper helper, BlockPos top, SurfaceWeather.Column c, int times) {
        ServerLevel level = helper.getLevel();
        RandomSource random = RandomSource.create(42);
        for (int i = 0; i < times; i++) {
            SurfaceWeather.visit(level, helper.absolutePos(top), c, random);
        }
    }

    private static int glaze(GameTestHelper helper, BlockPos pos) {
        BlockState s = helper.getBlockState(pos);
        return s.getBlock() instanceof GlazeBlock ? s.getValue(GlazeBlock.LAYERS) : 0;
    }

    /** Freezing rain builds two one-pixel layers and no more; a thaw melts it to nothing (not water). */
    @GameTest(template = SCRATCH)
    public static void glazeBuildsTwoLayersAndMeltsToNothing(GameTestHelper helper) {
        BlockPos top = new BlockPos(2, 2, 2);
        helper.setBlock(top.below(), Blocks.STONE);
        visit(helper, top, freezingRain(), 1);
        helper.assertTrue(glaze(helper, top) == 1, "one layer after an hour: " + glaze(helper, top));
        visit(helper, top, freezingRain(), 6);
        helper.assertTrue(glaze(helper, top) == 2, "capped at 2: " + glaze(helper, top));
        visit(helper, top, dry(8), 1);
        helper.assertTrue(glaze(helper, top) == 0 && helper.getBlockState(top).isAir(),
                "melted to nothing above freezing: " + helper.getBlockState(top));
        helper.succeed();
    }

    /** Glaze coats snow; snow doesn't settle on glaze; the thaw gives the snow back. */
    @GameTest(template = SCRATCH)
    public static void glazeCoatsSnowAndSnowStaysUnderIt(GameTestHelper helper) {
        BlockPos top = new BlockPos(4, 2, 2);
        helper.setBlock(top.below(), Blocks.STONE);
        helper.setBlock(top, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        visit(helper, top, freezingRain(), 3);
        BlockState s = helper.getBlockState(top);
        helper.assertTrue(s.getBlock() instanceof GlazeBlock && s.getValue(GlazeBlock.SNOW) == 3
                && s.getValue(GlazeBlock.LAYERS) == 2, "glazed snow (3 snow, 2 glaze): " + s);
        visit(helper, top, snow(), 6);
        helper.assertTrue(helper.getBlockState(top) == s, "no snow on glaze: " + helper.getBlockState(top));
        visit(helper, top, dry(8), 1);
        s = helper.getBlockState(top);
        helper.assertTrue(s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) == 3, "snow back: " + s);
        helper.succeed();
    }

    /** No glaze in block light 13 and up (next to glowstone: 14); it forms at 12 (two blocks off a 14 source). */
    @GameTest(template = SCRATCH, timeoutTicks = 60)
    public static void glazeSkipsBrightLight(GameTestHelper helper) {
        BlockPos lit = new BlockPos(2, 2, 6);
        BlockPos dim = new BlockPos(6, 2, 6);
        helper.setBlock(lit.below(), Blocks.STONE);
        helper.setBlock(dim.below(), Blocks.STONE);
        helper.setBlock(lit.east(), Blocks.GLOWSTONE);
        helper.runAfterDelay(10, () -> {
            visit(helper, lit, freezingRain(), 4);
            visit(helper, dim, freezingRain(), 4);
            helper.assertTrue(glaze(helper, lit) == 0, "glaze next to glowstone");
            helper.assertTrue(glaze(helper, dim) == 2, "no glaze away from the light: " + glaze(helper, dim));
            helper.succeed();
        });
    }

    /** Fully glazed leaves break now and then when another layer would come; the glaze goes with them. */
    @GameTest(template = SCRATCH)
    public static void fullyGlazedLeavesBreak(GameTestHelper helper) {
        BlockPos leaves = new BlockPos(6, 2, 2);
        helper.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        visit(helper, leaves.above(), freezingRain(), 2);
        helper.assertTrue(glaze(helper, leaves.above()) == 2, "glazed leaves");
        visit(helper, leaves.above(), freezingRain(), 40);
        helper.assertBlockNotPresent(Blocks.OAK_LEAVES, leaves);
        helper.assertTrue(glaze(helper, leaves.above()) == 0, "the glaze went with the leaves");
        helper.succeed();
    }

    /** An item slides far on glaze and stops short on stone. */
    @GameTest(template = SCRATCH, timeoutTicks = 80)
    public static void glazeIsSlippery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            helper.setBlock(new BlockPos(x, 1, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 7), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 7), SurfaceContent.GLAZE.get().defaultBlockState());
        }
        Vec3 stoneStart = helper.absoluteVec(new Vec3(0.5, 2.05, 1.5));
        Vec3 glazeStart = helper.absoluteVec(new Vec3(0.5, 2.1, 7.5));
        ItemEntity onStone = new ItemEntity(level, stoneStart.x, stoneStart.y, stoneStart.z,
                new ItemStack(Items.COBBLESTONE), 0.2, 0, 0);
        ItemEntity onGlaze = new ItemEntity(level, glazeStart.x, glazeStart.y, glazeStart.z,
                new ItemStack(Items.COBBLESTONE), 0.2, 0, 0);
        onStone.setPickUpDelay(32767);
        onGlaze.setPickUpDelay(32767);
        level.addFreshEntity(onStone);
        level.addFreshEntity(onGlaze);
        helper.runAfterDelay(40, () -> {
            double stone = onStone.getX() - stoneStart.x;
            double ice = onGlaze.getX() - glazeStart.x;
            onStone.discard();
            onGlaze.discard();
            helper.assertTrue(ice > 2 * stone && ice > 1.5,
                    "slid " + String.format("%.2f", ice) + " on glaze vs " + String.format("%.2f", stone) + " on stone");
            helper.succeed();
        });
    }

    /**
     * Hail reaches only what is out under the sky: a roof, glass or leaves overhead all shelter. (The test framework
     * roofs each test area with barriers, which count as shelter too, so the columns used are opened up first.)
     */
    @GameTest(template = SCRATCH)
    public static void hailOnlyHitsWhatIsOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 7; x += 2) {
            openSky(helper, new BlockPos(x, 0, 4));
        }
        for (int x = 1; x <= 7; x += 2) {
            helper.setBlock(new BlockPos(x, 1, 4), Blocks.STONE);
        }
        helper.setBlock(new BlockPos(3, 4, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 4, 4), Blocks.GLASS);
        helper.setBlock(new BlockPos(7, 4, 4), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        Pig out = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 2, 4));
        Pig roofed = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 4));
        Pig glassed = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 4));
        Pig leafed = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(7, 2, 4));
        helper.assertTrue(HailDamage.exposed(level, out), "out in the open");
        helper.assertFalse(HailDamage.exposed(level, roofed), "under stone");
        helper.assertFalse(HailDamage.exposed(level, glassed), "under glass");
        helper.assertFalse(HailDamage.exposed(level, leafed), "under leaves");
        helper.succeed();
    }

    /** Clears the test framework's barrier roof above a column of the test area. */
    private static void openSky(GameTestHelper helper, BlockPos column) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(column);
        for (int y = 1; y <= 24; y++) {
            BlockPos p = base.above(y);
            if (level.getBlockState(p).is(Blocks.BARRIER)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** Flat damage through armour, never below 1 health; anything on the head stops it and wears 3. */
    @GameTest(template = SCRATCH, timeoutTicks = 40)
    public static void hailDamageIsFlatAndStopsAtOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 7; x += 2) {
            helper.setBlock(new BlockPos(x, 1, 6), Blocks.STONE);
        }
        Pig bare = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 2, 6));
        Pig weak = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(3, 2, 6));
        Pig helmeted = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(5, 2, 6));
        Pig armoured = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(7, 2, 6));
        weak.setHealth(1);
        helmeted.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        armoured.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        // Let the armour's attributes apply (they do on the mob's tick).
        helper.runAfterDelay(3, () -> {
            float full = bare.getHealth();
            for (Pig p : new Pig[]{bare, weak, helmeted, armoured}) {
                HailDamage.hit(level, p);
            }
            helper.assertTrue(Math.abs(bare.getHealth() - (full - 1)) < 1e-4, "bare: " + bare.getHealth());
            helper.assertTrue(weak.getHealth() == 1, "never below 1: " + weak.getHealth());
            helper.assertTrue(helmeted.getHealth() == full, "the helmet takes it: " + helmeted.getHealth());
            helper.assertTrue(helmeted.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == HailDamage.HELMET_WEAR,
                    "helmet wear: " + helmeted.getItemBySlot(EquipmentSlot.HEAD).getDamageValue());
            helper.assertTrue(Math.abs(armoured.getHealth() - (full - 1)) < 1e-4,
                    "armour doesn't help: " + armoured.getHealth());
            helper.succeed();
        });
    }

    /** Hail knocks an exposed crop back a stage or more; one under glass is untouched. */
    @GameTest(template = SCRATCH)
    public static void hailKnocksExposedCropsBack(GameTestHelper helper) {
        BlockPos open = new BlockPos(2, 2, 4);
        BlockPos glassed = new BlockPos(6, 2, 4);
        BlockState ripe = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        for (BlockPos p : new BlockPos[]{open, glassed}) {
            helper.setBlock(p.below(), Blocks.FARMLAND);
            helper.setBlock(p, ripe);
        }
        helper.setBlock(glassed.above(2), Blocks.GLASS);
        visit(helper, open, hail(), 20);
        visit(helper, glassed.above(3), hail(), 20);
        int age = helper.getBlockState(open).getValue(CropBlock.AGE);
        helper.assertTrue(age < 7, "exposed wheat knocked back: age " + age);
        helper.assertTrue(helper.getBlockState(glassed).getValue(CropBlock.AGE) == 7, "wheat under glass untouched");
        helper.succeed();
    }

    private GlazeHailGameTests() {
    }
}
