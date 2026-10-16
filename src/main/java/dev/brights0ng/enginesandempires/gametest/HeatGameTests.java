package dev.brights0ng.enginesandempires.gametest;

import java.util.Optional;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.heat.HeatField;
import dev.brights0ng.enginesandempires.weather.heat.HeatSourceData;
import dev.brights0ng.enginesandempires.weather.surface.SurfaceWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8e-3: the heat and cold of the world's blocks as the air that snow, ice and water see
 * ({@link HeatField}, from the {@code heat_sources} data map). The air given is passed in, so the weather doesn't
 * matter. The probes far from a source are taken straight above it, away from the neighbouring cells' own sources.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class HeatGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final String BATCH = "heat";

    private static BlockState with(Block block, String property, String value) {
        Property<?> p = block.getStateDefinition().getProperty(property);
        if (p == null) {
            throw new IllegalStateException(block + " has no property " + property);
        }
        return set(block.defaultBlockState(), p, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> p, String value) {
        return state.setValue(p, p.getValue(value).orElseThrow(() -> new IllegalStateException(p + " has no " + value)));
    }

    private static Optional<Block> block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));
    }

    private static double at(GameTestHelper helper, BlockPos relative, double air) {
        return HeatField.apply(helper.getLevel(), helper.absolutePos(relative), air);
    }

    private static void near(GameTestHelper helper, String what, double expected, double got) {
        helper.assertTrue(Math.abs(expected - got) < 0.01, what + ": expected " + expected + ", got " + got);
    }

    // ---- the table ----

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void theSeededTableIsThere(GameTestHelper helper) {
        check(helper, Blocks.LAVA.defaultBlockState(), 16.67, 7, 111.1, 537.8);
        check(helper, Blocks.MAGMA_BLOCK.defaultBlockState(), 11.11, 3, 26.67, Double.NaN);
        check(helper, Blocks.ICE.defaultBlockState(), -5.56, 4, 13.33, 0.56);
        check(helper, Blocks.PACKED_ICE.defaultBlockState(), -8.33, 4, 26.67, -8.89);
        // Direct entries only: a tag entry would beat them (found in the first run), so the table lists ice by block
        check(helper, Blocks.BLUE_ICE.defaultBlockState(), -11.11, 4, 35.56, -17.78);
        check(helper, Blocks.FROSTED_ICE.defaultBlockState(), -3.33, 4, 15, 0.56);
        check(helper, Blocks.FIRE.defaultBlockState(), 13.89, 7, 27.78, 204.4);
        check(helper, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 13.89, 7, 27.78, 204.4);
        check(helper, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 13.89, 7, 27.78, 204.4);
        helper.assertTrue(HeatField.variantOf(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false)) == null,
                "a campfire that is out gives nothing");
        helper.assertTrue(HeatField.variantOf(Blocks.STONE.defaultBlockState()) == null, "stone gives nothing");
        block("create:blaze_burner").ifPresent(burner -> {
            check(helper, with(burner, "blaze", "seething"), 16.67, 6, 33.33, 204.4);
            check(helper, with(burner, "blaze", "kindled"), 11.11, 5, 25, 204.4);
            check(helper, with(burner, "blaze", "fading"), 6.67, 4, 16.67, 204.4);
            check(helper, with(burner, "blaze", "smouldering"), 2.78, 3, 16.67, 204.4);
            helper.assertTrue(HeatField.variantOf(with(burner, "blaze", "none")) == null, "an unlit burner gives nothing");
        });
        block("cold_sweat:icebox").ifPresent(box -> {
            check(helper, with(box, "frosted", "true"), -8.33, 7, 20, 0);
            helper.assertTrue(HeatField.variantOf(with(box, "frosted", "false")) == null, "an icebox without ice gives nothing");
        });
        block("cold_sweat:boiler").ifPresent(boiler -> check(helper, with(boiler, "lit", "true"), 8.33, 7, 20, 100));
        helper.succeed();
    }

    private static void check(GameTestHelper helper, BlockState state, double change, double range, double cap, double limit) {
        HeatSourceData.Variant v = HeatField.variantOf(state);
        helper.assertTrue(v != null, state + " should be a source");
        String what = state.toString();
        near(helper, what + " change", change, v.change());
        near(helper, what + " range", range, v.range());
        near(helper, what + " cap", cap, v.maxEffect());
        if (Double.isNaN(limit)) {
            helper.assertTrue(Double.isNaN(v.limit()), what + " has no limit: " + v.limit());
        } else {
            near(helper, what + " limit", limit, v.limit());
        }
    }

    // ---- warmth ----

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void aLitCampfireWarmsTheAirNearItAndNotFarOrWhenOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos fire = new BlockPos(3, 2, 3);
        helper.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        HeatField.invalidate(level);

        double touching = at(helper, fire, -10);
        near(helper, "at the fire: all of it", -10 + 13.89, touching);
        double three = at(helper, fire.above(3), -10);
        helper.assertTrue(three > -10 && three < touching, "three blocks away: warmer than the air, cooler than at the fire: " + three);
        near(helper, "beyond its range", -10, at(helper, fire.above(8), -10));

        helper.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        HeatField.invalidate(level);
        near(helper, "once it is out", -10, at(helper, fire, -10));
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void campfiresAddUpOnlyToTheirCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 5; x++) {
            helper.setBlock(new BlockPos(x, 2, 3), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        }
        HeatField.invalidate(level);
        // Five fires at about +13.9 each would be +69; the kind is capped at 27.78
        near(helper, "capped", -10 + 27.78, at(helper, new BlockPos(3, 2, 3), -10));
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void aFireCannotWarmAirPastItsLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos fire = new BlockPos(3, 2, 3);
        helper.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        HeatField.invalidate(level);
        near(helper, "up to its limit", 204.4, at(helper, fire, 200));
        near(helper, "air already hotter is left alone", 250, at(helper, fire, 250));
        helper.succeed();
    }

    // ---- cold ----

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void blueIceFreezesAndAnIceboxNeverDoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = new BlockPos(3, 2, 3);

        helper.setBlock(pos, Blocks.BLUE_ICE);
        HeatField.invalidate(level);
        near(helper, "blue ice on a mild day", 5 - 11.11, at(helper, pos, 5));
        near(helper, "blue ice stops at its own limit", -17.78, at(helper, pos, -10));

        helper.setBlock(pos, Blocks.ICE);
        HeatField.invalidate(level);
        near(helper, "ice on a warm day", 10 - 5.56, at(helper, pos, 10));
        helper.assertTrue(at(helper, pos, 2) >= 0.56 - 1e-9, "plain ice doesn't take the air below its 0.56 limit: " + at(helper, pos, 2));
        near(helper, "ice leaves air already colder alone", -4, at(helper, pos, -4));

        Optional<Block> icebox = block("cold_sweat:icebox");
        if (icebox.isPresent()) {
            helper.setBlock(pos, with(icebox.get(), "frosted", "true"));
            HeatField.invalidate(level);
            near(helper, "an icebox on a hot day", 25 - 8.33, at(helper, pos, 25));
            near(helper, "but never below freezing", 0, at(helper, pos, 5));
            near(helper, "and it leaves colder air alone", -3, at(helper, pos, -3));
            // The reason for all this: a stock icebox cannot freeze the water of a farm, whatever the day
            for (double air : new double[]{-1, 0, 3, 8, 30}) {
                double got = at(helper, pos, air);
                helper.assertTrue(got >= Math.min(air, 0) - 1e-9, "icebox at " + air + " C gave " + got);
            }
        }
        helper.succeed();
    }

    // ---- what the ground does ----

    /** Snow beside a fire melts on a freezing day, as it does when the air is warm; snow far from it stays. */
    @GameTest(template = SCRATCH, batch = BATCH)
    public static void snowMeltsBesideAFireOnAFreezingDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        BlockPos snow = new BlockPos(4, 2, 3);
        helper.setBlock(snow, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        HeatField.invalidate(level);

        double air = -5;
        double beside = HeatField.apply(level, helper.absolutePos(snow), air);
        helper.assertTrue(beside > 0, "beside the fire the ground is above freezing: " + beside);
        double far = HeatField.apply(level, helper.absolutePos(snow).above(10), air);
        near(helper, "far from it: the plain air", air, far);

        RandomSource random = RandomSource.create(5);
        SurfaceWeather.Column warm = new SurfaceWeather.Column(beside, null, 0, false, 0, 0);
        for (int i = 0; i < 60 && helper.getBlockState(snow).is(Blocks.SNOW); i++) {
            SurfaceWeather.visit(level, helper.absolutePos(snow), warm, random);
        }
        helper.assertTrue(!helper.getBlockState(snow).is(Blocks.SNOW), "the snow beside the fire melted away");

        helper.setBlock(snow, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        SurfaceWeather.Column cold = new SurfaceWeather.Column(far, null, 0, false, 0, 0);
        for (int i = 0; i < 60; i++) {
            SurfaceWeather.visit(level, helper.absolutePos(snow), cold, random);
        }
        helper.assertTrue(helper.getBlockState(snow).is(Blocks.SNOW), "and on a freezing day with no fire, it stays");
        helper.succeed();
    }

    private HeatGameTests() {
    }

    // ---- 8e-4: sources at work ----

    private static void fires(GameTestHelper helper, BlockPos... at) {
        for (BlockPos p : at) {
            helper.setBlock(p, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        }
        HeatField.invalidate(helper.getLevel());
    }

    /** Runs the chunk's source visits until {@code done} holds (or {@code max} rounds), as the server would over time. */
    private static boolean visitUntil(GameTestHelper helper, int max, java.util.function.BooleanSupplier done) {
        ServerLevel level = helper.getLevel();
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(helper.absolutePos(new BlockPos(3, 2, 3)));
        RandomSource random = RandomSource.create(11);
        for (int i = 0; i < max && !done.getAsBoolean(); i++) {
            SurfaceWeather.sourceVisits(level, cp, random);
        }
        return done.getAsBoolean();
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void iceUnderARoofThawsBesideFiresAndNotWithoutThem(GameTestHelper helper) {
        BlockPos ice = new BlockPos(3, 2, 3);
        helper.setBlock(ice, Blocks.ICE);
        helper.setBlock(ice.above(), Blocks.STONE);
        helper.setBlock(ice.below(), Blocks.STONE);
        fires(helper, new BlockPos(2, 2, 3), new BlockPos(4, 2, 3), new BlockPos(3, 2, 2));
        boolean thawed = visitUntil(helper, 3000, () -> !helper.getBlockState(ice).is(Blocks.ICE));
        helper.assertTrue(thawed, "ice under a roof, among fires, thaws (the sky never sees it)");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void snowUnderARoofMeltsBesideFires(GameTestHelper helper) {
        BlockPos snow = new BlockPos(3, 2, 3);
        helper.setBlock(snow.below(), Blocks.STONE);
        helper.setBlock(snow, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        helper.setBlock(snow.above(), Blocks.STONE);
        fires(helper, new BlockPos(2, 2, 3), new BlockPos(4, 2, 3), new BlockPos(3, 2, 2));
        boolean gone = visitUntil(helper, 4000, () -> !helper.getBlockState(snow).is(Blocks.SNOW));
        helper.assertTrue(gone, "snow under a roof, among fires, melts");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void shelteredIceWithNoWarmSourceInReachIsLeftAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ice = new BlockPos(3, 2, 3);
        helper.setBlock(ice.below(), Blocks.STONE);
        helper.setBlock(ice.above(), Blocks.STONE);
        helper.setBlock(ice, Blocks.ICE);
        HeatField.invalidate(level);
        // Plain ice is itself a cold source: it cools its own air and never warms it, so nothing here may melt it,
        // however warm the day (the sky never sees it)
        RandomSource random = RandomSource.create(3);
        for (int i = 0; i < 500; i++) {
            SurfaceWeather.sourceSample(level, helper.absolutePos(ice), random);
        }
        helper.assertBlockPresent(Blocks.ICE, ice);
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void blueIceFreezesAPondAndFlowingWaterAndAnIceboxDoNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos water = new BlockPos(3, 2, 3);

        // Blue ice all round and below a still pond with air over it
        helper.setBlock(water, Blocks.WATER);
        for (BlockPos p : new BlockPos[]{water.below(), water.east(), water.west(), water.north(), water.south()}) {
            helper.setBlock(p, Blocks.BLUE_ICE);
        }
        HeatField.invalidate(level);
        BlockPos abs = helper.absolutePos(water);
        double outside = SurfaceWeather.temperatureAt(level, abs);
        helper.assertTrue(HeatField.apply(level, abs, outside) < -1, "blue ice puts the pond's air below -1 C: " + outside + " -> "
                + HeatField.apply(level, abs, outside));
        helper.assertTrue(visitUntil(helper, 3000, () -> helper.getBlockState(water).is(Blocks.ICE)),
                "a pond among blue ice freezes");

        // A flowing (non-source) block beside blue ice stays as it is
        BlockPos flowing = new BlockPos(3, 2, 3);
        helper.setBlock(flowing, Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 1));
        RandomSource random = RandomSource.create(9);
        for (int i = 0; i < 500; i++) {
            SurfaceWeather.sourceSample(level, helper.absolutePos(flowing), random);
        }
        helper.assertTrue(!helper.getBlockState(flowing).is(Blocks.ICE), "flowing water doesn't freeze");

        // Plain ice, and an icebox, never take the pond below their limits, so they cannot freeze it
        for (BlockPos p : new BlockPos[]{water.below(), water.east(), water.west(), water.north(), water.south()}) {
            helper.setBlock(p, Blocks.ICE);
        }
        helper.setBlock(water, Blocks.WATER);
        HeatField.invalidate(level);
        for (int i = 0; i < 500; i++) {
            SurfaceWeather.sourceSample(level, abs, random);
        }
        helper.assertBlockPresent(Blocks.WATER, water);
        Optional<Block> icebox = block("cold_sweat:icebox");
        if (icebox.isPresent()) {
            BlockState box = with(icebox.get(), "frosted", "true");
            for (BlockPos p : new BlockPos[]{water.below(), water.east(), water.west(), water.north(), water.south()}) {
                helper.setBlock(p, box);
            }
            HeatField.invalidate(level);
            for (int i = 0; i < 500; i++) {
                SurfaceWeather.sourceSample(level, abs, random);
            }
            helper.assertBlockPresent(Blocks.WATER, water);
        }
        helper.succeed();
    }
}
