package dev.brights0ng.enginesandempires.gametest;

import org.joml.Vector2i;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;
import dev.brights0ng.enginesandempires.weather.climate.PersonClimate;
import dev.brights0ng.enginesandempires.weather.climate.SunWarmth;
import dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import dev.brights0ng.enginesandempires.weather.sky.StormLight;
import dev.brights0ng.enginesandempires.weather.wind.WindColumn;
import dev.brights0ng.enginesandempires.weather.wind.WindConfig;
import dev.brights0ng.enginesandempires.weather.wind.WindParams;
import dev.brights0ng.enginesandempires.weather.wind.WindSources;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8e-2: the sun's warmth through blocks, ships and clouds ({@link SunWarmth}), and the wind a person feels
 * ({@link PersonClimate}): shelter, the person's own motion, height. Noon is the celestial time 0; the warmth maximum
 * is passed in, so the config doesn't matter.
 *
 * <p>The game test cells are walled and roofed with barrier blocks (which the probes rightly see), so everything here
 * happens {@link #UP} blocks above the cell, in open air. Tests that build blocks, and the wind tests that look 16
 * blocks sideways, each run in a batch of their own so that a neighbouring cell's building isn't in their way.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class PersonClimateGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final double MAX = 8.0;
    private static final int REACH = 96;
    private static final double NOON = 0.0;
    private static final double MIDNIGHT = 0.5;
    /** Blocks above the cell where the tests happen. */
    private static final int UP = 30;

    // ---- sun ----

    /** The warmth on a person standing {@link #UP} above the cell's middle, with their eyes 1.6 up. */
    private static double warmth(GameTestHelper helper, double timeOfDay) {
        Vec3 feet = helper.absoluteVec(new Vec3(3.5, UP, 3.5));
        return SunWarmth.at(helper.getLevel(), feet.add(0, 1.6, 0), BlockPos.containing(feet), timeOfDay, MAX, REACH);
    }

    /** A block position {@code dy} above the person's feet, over their column. */
    private static BlockPos over(int dy) {
        return new BlockPos(3, UP + dy, 3);
    }

    @GameTest(template = SCRATCH, batch = "person_sun_open")
    public static void openSkyAtNoonGivesTheFullWarmth(GameTestHelper helper) {
        double w = warmth(helper, NOON);
        helper.assertTrue(Math.abs(w - MAX) < 0.01, "clear sky at noon is the maximum, " + MAX + ": " + w);
        helper.assertTrue(warmth(helper, MIDNIGHT) == 0, "no sun at midnight: " + warmth(helper, MIDNIGHT));
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "person_sun_blocks")
    public static void blocksThatLetLightThroughGiveAQuarterAndSolidOnesNone(GameTestHelper helper) {
        BlockPos roof = over(4);
        Object[][] cases = {
                {Blocks.GLASS, 0.25}, {Blocks.OAK_LEAVES, 0.25}, {Blocks.OAK_SLAB, 0.25}, {Blocks.WATER, 0.25},
                {Blocks.STONE, 0.0}, {Blocks.DIRT, 0.0}, {Blocks.AIR, 1.0}, {Blocks.TORCH, 1.0}};
        try {
            for (Object[] c : cases) {
                Block block = (Block) c[0];
                helper.setBlock(roof, block);
                double w = warmth(helper, NOON);
                helper.assertTrue(Math.abs(w - MAX * (double) c[1]) < 0.01,
                        block + " overhead should leave " + c[1] + " of the warmth: " + w);
            }
            // Several see-through blocks in a row are still one quarter
            helper.setBlock(over(4), Blocks.GLASS);
            helper.setBlock(over(5), Blocks.OAK_LEAVES);
            helper.setBlock(over(6), Blocks.GLASS);
            double w = warmth(helper, NOON);
            helper.assertTrue(Math.abs(w - MAX * 0.25) < 0.01, "glass, leaves and glass are still a quarter: " + w);
        } finally {
            for (int dy = 4; dy <= 6; dy++) {
                helper.setBlock(over(dy), Blocks.AIR);
            }
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "person_sun_ship", timeoutTicks = 80)
    public static void aShipsBlocksFollowTheSameRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel glass = spawnBlock(helper, new Vec3(3.5, 40, 3.5), Blocks.GLASS.defaultBlockState());
        helper.runAfterDelay(3, () -> {
            try {
                BoundingBox3dc box = glass.boundingBox();
                Vec3 eye = new Vec3((box.minX() + box.maxX()) / 2, box.minY() - 6, (box.minZ() + box.maxZ()) / 2);
                double under = SunWarmth.at(level, eye, BlockPos.containing(eye.x, eye.y - 1.6, eye.z), NOON, MAX, REACH);
                helper.assertTrue(Math.abs(under - MAX * 0.25) < 0.01, "under a glass ship: a quarter: " + under);
                Vec3 beside = eye.add(box.maxX() - box.minX() + 3, 0, 0);
                double open = SunWarmth.at(level, beside, BlockPos.containing(beside.x, beside.y - 1.6, beside.z), NOON, MAX, REACH);
                helper.assertTrue(Math.abs(open - MAX) < 0.01, "beside it: all of it: " + open);
            } finally {
                remove(helper, glass);
            }
            ServerSubLevel stone = spawnBlock(helper, new Vec3(3.5, 40, 3.5), Blocks.STONE.defaultBlockState());
            helper.runAfterDelay(3, () -> {
                try {
                    BoundingBox3dc box = stone.boundingBox();
                    Vec3 eye = new Vec3((box.minX() + box.maxX()) / 2, box.minY() - 6, (box.minZ() + box.maxZ()) / 2);
                    double under = SunWarmth.at(level, eye, BlockPos.containing(eye.x, eye.y - 1.6, eye.z), NOON, MAX, REACH);
                    helper.assertTrue(under == 0, "under a stone ship: none: " + under);
                } finally {
                    remove(helper, stone);
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = SCRATCH, batch = "person_sun_cloud", timeoutTicks = 80)
    public static void aThickCloudFadesTheSunAndTheStrongerObscurerWins(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WeatherSim sim = WeatherSim.of(level);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        sim.field().ensure(at.getX(), at.getZ(), 2000, sim.readEnv(), sim.time());
        SimCloud cloud = CloudWorld.spawn(level, CloudType.CUMULONIMBUS_CAPILLATUS, at.getX(), at.getZ());
        helper.assertTrue(cloud != null, "a storm");
        helper.runAfterDelay(3, () -> {
            try {
                StormLight.invalidate(level);
                double cloudOnly = warmth(helper, NOON);
                helper.assertTrue(cloudOnly < 0.5 * MAX, "a storm over the head takes most of the sun: " + cloudOnly);
                helper.assertTrue(cloudOnly >= 0, "never below nothing: " + cloudOnly);

                // A pane of glass under thick cloud: the lower of the two, not both
                helper.setBlock(over(4), Blocks.GLASS);
                double both = warmth(helper, NOON);
                double expected = Math.min(cloudOnly, MAX * 0.25);
                helper.assertTrue(Math.abs(both - expected) < 0.01,
                        "glass under cloud is the lower of the two (" + expected + "): " + both);
            } finally {
                helper.setBlock(over(4), Blocks.AIR);
                sim.cloudSim().clouds().remove(cloud);
                StormLight.invalidate(level);
            }
            helper.succeed();
        });
    }

    // ---- wind ----

    @GameTest(template = SCRATCH)
    public static void theElevationCoolingDefaultsToFiveDegreesPer100Blocks(GameTestHelper helper) {
        helper.assertTrue(Math.abs(dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.heightCoolingPer100() - 5.0) < 1e-9,
                "5 C per 100 blocks: " + dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.heightCoolingPer100());
        double scale = dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.heightCoolingScale();
        double expected = dev.brights0ng.enginesandempires.weather.climate.Temperature.scaleFor(5.0);
        helper.assertTrue(Math.abs(scale - expected) < 1e-9, "and the scale follows it: " + scale + " vs " + expected);
        helper.succeed();
    }

    private static Vec3 at(GameTestHelper helper) {
        return helper.absoluteVec(new Vec3(3.5, UP, 3.5));
    }

    @GameTest(template = SCRATCH, batch = "person_wind_open")
    public static void aPersonInOpenAirFeelsTheWholeWindAndTheirOwnMotionCountsAgainstIt(GameTestHelper helper) {
        WindSources.setOverride(WindColumn.uniform(6, 0));
        try {
            ServerLevel level = helper.getLevel();
            Vec3 p = at(helper);
            double open = PersonClimate.windOn(level, p, 0, 0);
            helper.assertTrue(Math.abs(open - 6) < 0.01, "6 m/s in the open: " + open);
            helper.assertTrue(PersonClimate.windOn(level, p, 6, 0) < 0.01, "riding along with it: still air");
            helper.assertTrue(Math.abs(PersonClimate.windOn(level, p, -4, 0) - 10) < 0.01, "walking into it: 6 + 4");
            helper.assertTrue(Math.abs(PersonClimate.windOn(level, p, 0, 8) - 10) < 0.01, "across it: the hypotenuse of 6 and 8");
        } finally {
            WindSources.setOverride(null);
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "person_wind_open")
    public static void theWindGrowsWithHeightLikeAShips(GameTestHelper helper) {
        WindSources.setOverride(new WindColumn(2, 0, 20, 0));
        try {
            ServerLevel level = helper.getLevel();
            WindParams params = WindConfig.params();
            Vec3 low = at(helper);
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(low.x),
                    (int) Math.floor(low.z));
            helper.assertTrue(low.y < ground + params.surfaceLayer(), "the test spot is within the surface layer: " + low.y
                    + " vs ground " + ground);
            double surface = PersonClimate.windOn(level, low, 0, 0);
            helper.assertTrue(Math.abs(surface - 2) < 0.01, "near the ground: the surface wind: " + surface);

            // aloftHeight is a world y, not a height above the ground
            Vec3 high = new Vec3(low.x, params.aloftHeight() + 40, low.z);
            helper.assertTrue(high.y < level.getMaxBuildHeight() - 20, "room to test aloft: " + high.y);
            double aloft = PersonClimate.windOn(level, high, 0, 0);
            helper.assertTrue(Math.abs(aloft - 20) < 0.01, "high up: the aloft wind: " + aloft);

            double mid = (ground + params.surfaceLayer() + params.aloftHeight()) / 2;
            double between = PersonClimate.windOn(level, new Vec3(low.x, mid, low.z), 0, 0);
            helper.assertTrue(between > 2.5 && between < 19.5, "halfway: in between: " + between);
        } finally {
            WindSources.setOverride(null);
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "person_wind_sealed")
    public static void aSealedRoomShelters(GameTestHelper helper) {
        WindSources.setOverride(WindColumn.uniform(10, 0));
        try {
            // A stone shell around a 1x2 room, with the person in it
            for (int x = 2; x <= 4; x++) {
                for (int y = UP - 1; y <= UP + 2; y++) {
                    for (int z = 2; z <= 4; z++) {
                        boolean room = x == 3 && z == 3 && (y == UP || y == UP + 1);
                        helper.setBlock(new BlockPos(x, y, z), room ? Blocks.AIR : Blocks.STONE);
                    }
                }
            }
            double wind = PersonClimate.windOn(helper.getLevel(), at(helper), 0, 0);
            helper.assertTrue(wind < 0.01, "no wind inside walls and a roof: " + wind);
        } finally {
            WindSources.setOverride(null);
            clear(helper, 2, 4, UP - 1, UP + 2, 2, 4);
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "person_wind_wall")
    public static void aWallOnTheUpwindSideShelters(GameTestHelper helper) {
        WindSources.setOverride(WindColumn.uniform(10, 0)); // blowing toward +x: the west side is the upwind one
        try {
            ServerLevel level = helper.getLevel();
            for (int y = UP; y <= UP + 1; y++) {
                for (int z = 2; z <= 4; z++) {
                    helper.setBlock(new BlockPos(2, y, z), Blocks.STONE);
                }
            }
            double upwind = PersonClimate.windOn(level, at(helper), 0, 0);
            helper.assertTrue(upwind < 0.01, "a wall upwind blocks it: " + upwind);
            // The same wall on the lee side does nothing
            clear(helper, 2, 2, UP, UP + 1, 2, 4);
            for (int y = UP; y <= UP + 1; y++) {
                for (int z = 2; z <= 4; z++) {
                    helper.setBlock(new BlockPos(4, y, z), Blocks.STONE);
                }
            }
            double lee = PersonClimate.windOn(level, at(helper), 0, 0);
            helper.assertTrue(Math.abs(lee - 10) < 0.01, "a wall downwind doesn't: " + lee);
        } finally {
            WindSources.setOverride(null);
            clear(helper, 2, 4, UP, UP + 1, 2, 4);
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "person_wind_ship", timeoutTicks = 80)
    public static void aShipsRoofShelters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WindParams params = WindConfig.params();
        ServerSubLevel roof = spawnBlock(helper, new Vec3(3.5, 50, 3.5), Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(3, () -> {
            try {
                BoundingBox3dc box = roof.boundingBox();
                double cx = (box.minX() + box.maxX()) / 2;
                double cz = (box.minZ() + box.maxZ()) / 2;
                Vec3 under = new Vec3(cx, box.minY() - 4.5, cz);
                double sheltered = PersonClimate.exposure(level, under, 1, 0, params);
                double expected = 1 - params.roofWeight();
                helper.assertTrue(Math.abs(sheltered - expected) < 0.02,
                        "a roof alone cuts by its weight (exposure " + expected + "): " + sheltered);
                Vec3 beside = under.add(box.maxX() - box.minX() + 3, 0, 0);
                double open = PersonClimate.exposure(level, beside, 1, 0, params);
                helper.assertTrue(open > 0.99, "beside the ship: open: " + open);
            } finally {
                remove(helper, roof);
            }
            helper.succeed();
        });
    }

    private static void clear(GameTestHelper helper, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    // ---- ships ----

    /** Spawns a one-block physics object at a position relative to the test (the way Sable's own tests do). */
    private static ServerSubLevel spawnBlock(GameTestHelper helper, Vec3 relative, BlockState state) {
        ServerLevel level = helper.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        Pose3d pose = new Pose3d();
        pose.position().set(origin.getX() + relative.x, origin.getY() + relative.y, origin.getZ() + relative.z);
        SubLevel subLevel = container.allocateNewSubLevel(pose);
        LevelPlot plot = subLevel.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, state, 3);
        subLevel.updateLastPose();
        return (ServerSubLevel) subLevel;
    }

    private static void remove(GameTestHelper helper, ServerSubLevel subLevel) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
        LevelPlot plot = subLevel.getPlot();
        Vector2i origin = container.getOrigin();
        container.removeSubLevel(plot.plotPos.x - origin.x, plot.plotPos.z - origin.y, SubLevelRemovalReason.REMOVED);
    }

    private PersonClimateGameTests() {
    }
}
