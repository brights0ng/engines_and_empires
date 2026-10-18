package dev.brights0ng.meteodyne.sable.gametest;

import org.joml.Vector2i;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.PersonClimate;
import dev.brights0ng.meteodyne.climate.SunWarmth;
import dev.brights0ng.meteodyne.gametest.PersonClimateGameTests;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.wind.WindConfig;
import dev.brights0ng.paradynelib.wind.WindParams;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8e-2, on ships: a ship's blocks shade the sun by the same rule as the world's, and a ship's roof
 * shelters from the wind (split from {@code PersonClimateGameTests} in split step 2c).
 *
 * <p>Needs Sable: not a {@code @GameTestHolder} (NeoForge would load every holder, Sable or not), but registered by
 * {@code MeteodyneSable} only when Sable is loaded, each test naming Meteodyne's template namespace itself.
 */
@PrefixGameTestTemplate(false)
public final class ShipPersonClimateGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final double MAX = PersonClimateGameTests.MAX;
    private static final int REACH = PersonClimateGameTests.REACH;
    private static final double NOON = PersonClimateGameTests.NOON;

    @GameTest(templateNamespace = Meteodyne.MODID, template = SCRATCH, batch = "person_sun_ship", timeoutTicks = 80)
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

    @GameTest(templateNamespace = Meteodyne.MODID, template = SCRATCH, batch = "person_wind_ship", timeoutTicks = 80)
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

    private ShipPersonClimateGameTests() {
    }
}
