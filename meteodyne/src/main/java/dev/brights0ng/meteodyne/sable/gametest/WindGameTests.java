package dev.brights0ng.meteodyne.sable.gametest;

import org.joml.Vector2i;
import org.joml.Vector3d;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.ParadyneLib;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.wind.ShelterProbe;
import dev.brights0ng.paradynelib.sable.wind.ShipWind;
import dev.brights0ng.paradynelib.sable.wind.WindContent;
import dev.brights0ng.paradynelib.sable.wind.WindShips;
import dev.brights0ng.paradynelib.wind.Shelter;
import dev.brights0ng.paradynelib.wind.WindColumn;
import dev.brights0ng.paradynelib.wind.WindParams;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of the wind push, with the wind forced by {@link WindSources#setOverride} (the game test server has no
 * Project Atmosphere): a block floating in an east wind drifts east, calm air leaves it alone, a sealed box is fully
 * sheltered, and the wind force group is in Sable's registry.
 *
 * <p>The override is global, so the push tests run in batches of their own. The floating block is spawned well above the
 * test's walled room, where nothing shelters it.
 *
 * <p>Needs Sable: not a {@code @GameTestHolder} (NeoForge would load every holder, Sable or not), but registered by
 * {@code MeteodyneSable} only when Sable is loaded, each test naming Meteodyne's template namespace itself.
 */
@PrefixGameTestTemplate(false)
public final class WindGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(templateNamespace = Meteodyne.MODID, template = SCRATCH)
    public static void windForceGroupIsRegistered(GameTestHelper helper) {
        // The force group is the library's (WindContent), so its id is in the library's namespace
        ResourceLocation id = ParadyneLib.id("wind");
        helper.assertTrue(WindContent.WIND.isBound(), "the wind force group should be registered");
        helper.assertTrue(ForceGroups.REGISTRY.containsKey(id), "Sable's force group registry should hold " + id);
        helper.succeed();
    }

    @GameTest(templateNamespace = Meteodyne.MODID, template = SCRATCH, batch = "wind_push", timeoutTicks = 100)
    public static void aFloatingBlockDriftsDownwind(GameTestHelper helper) {
        double[] east = WindColumn.fromYaw(20, 270);
        WindSources.setOverride(WindColumn.uniform(east[0], east[1]));
        ServerSubLevel block = spawnBlock(helper, new Vector3d(3.5, 24, 3.5), Blocks.GLASS.defaultBlockState());
        helper.runAfterDelay(20, () -> {
            Vector3d velocity = velocity(helper, block);
            ShipWind ship = WindShips.peek(block);
            WindSources.setOverride(null);
            remove(helper, block);
            helper.assertTrue(ship != null && ship.built(), "the block should have been measured");
            helper.assertTrue(ship.exposure() > 0.99, "high above the room it should be in open air: " + ship.exposure());
            helper.assertTrue(velocity.x > 1.0, "an east wind should push it east: velocity " + velocity);
            helper.assertTrue(Math.abs(velocity.z) < 0.1 * velocity.x, "and not sideways: velocity " + velocity);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = Meteodyne.MODID, template = SCRATCH, batch = "wind_calm", timeoutTicks = 100)
    public static void calmAirLeavesABlockAlone(GameTestHelper helper) {
        WindSources.setOverride(WindColumn.CALM);
        ServerSubLevel block = spawnBlock(helper, new Vector3d(3.5, 24, 3.5), Blocks.GLASS.defaultBlockState());
        helper.runAfterDelay(20, () -> {
            Vector3d velocity = velocity(helper, block);
            WindSources.setOverride(null);
            remove(helper, block);
            helper.assertTrue(Math.hypot(velocity.x, velocity.z) < 0.05, "calm air should not move it: velocity " + velocity);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = Meteodyne.MODID, template = SCRATCH)
    public static void aSealedBoxIsFullySheltered(GameTestHelper helper) {
        BlockPos cell = new BlockPos(3, 2, 3);
        for (BlockPos wall : new BlockPos[]{cell.above(), cell.north(), cell.south(), cell.west(), cell.east()}) {
            helper.setBlock(wall, Blocks.STONE);
        }
        BlockPos at = helper.absolutePos(cell);
        double[] coverage = new double[Shelter.FACES];
        WindParams p = WindParams.DEFAULTS;
        ShelterProbe.measure(helper.getLevel(), at.getX(), at.getY(), at.getZ(), at.getX() + 1, at.getY() + 1,
                at.getZ() + 1, p.sideReach(), p.roofReach(), coverage);
        for (int face = 0; face < Shelter.FACES; face++) {
            helper.assertTrue(coverage[face] == 1, "face " + face + " should be fully covered: " + coverage[face]);
        }
        helper.assertTrue(Shelter.exposure(coverage, 0.6, -0.8, p.roofWeight()) == 0, "a sealed box is immune");
        helper.succeed();
    }

    /** Spawns a one-block physics object at a position relative to the test (the way Sable's own tests do). */
    private static ServerSubLevel spawnBlock(GameTestHelper helper, Vector3d relative, BlockState state) {
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

    private static Vector3d velocity(GameTestHelper helper, ServerSubLevel subLevel) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
        return container.physicsSystem().getPhysicsHandle(subLevel).getLinearVelocity(new Vector3d());
    }

    private static void remove(GameTestHelper helper, ServerSubLevel subLevel) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
        LevelPlot plot = subLevel.getPlot();
        Vector2i origin = container.getOrigin();
        container.removeSubLevel(plot.plotPos.x - origin.x, plot.plotPos.z - origin.y, SubLevelRemovalReason.REMOVED);
    }

    private WindGameTests() {
    }
}
