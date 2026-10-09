package dev.brights0ng.enginesandempires.gametest;

import org.joml.Vector2i;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.ships.ShipCover;
import dev.brights0ng.enginesandempires.weather.ships.ShipWeather;
import dev.brights0ng.enginesandempires.weather.surface.GlazeBlock;
import dev.brights0ng.enginesandempires.weather.surface.HailDamage;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Phase 5d of the weather backbone: ships (Sable sub-levels) as cover from the sky, and weather on their decks. Each test
 * spawns a one-block ship over the test area (it falls slowly at first; the checks run a few ticks in) and opens the
 * test framework's barrier roof over it.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class ShipWeatherGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    /** A ship's block shelters the column under it (hail and rain), not the air above or beside it. */
    @GameTest(template = SCRATCH, timeoutTicks = 60)
    public static void aShipSheltersWhatIsUnderIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        openSky(helper);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.STONE);
        ServerSubLevel ship = spawnBlock(helper, 4.5, 7, 4.5, Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(3, () -> {
            BoundingBox3dc box = ship.boundingBox();
            double cx = (box.minX() + box.maxX()) / 2;
            double cz = (box.minZ() + box.maxZ()) / 2;
            double top = ShipCover.top(level, cx, cz, box.minY() - 4);
            helper.assertTrue(Math.abs(top - box.maxY()) < 0.1, "deck top " + top + " vs bounds " + box.maxY());
            helper.assertTrue(ShipCover.covered(level, cx, box.minY() - 2, cz), "under the ship is covered");
            helper.assertFalse(ShipCover.covered(level, cx, box.maxY() + 0.5, cz), "above it isn't");
            helper.assertFalse(ShipCover.covered(level, box.maxX() + 2, box.minY() - 2, cz), "beside it isn't");
            Pig under = (Pig) EntityType.PIG.create(level);
            under.setNoAi(true);
            under.moveTo(cx, helper.absolutePos(new BlockPos(4, 2, 4)).getY(), cz);
            level.addFreshEntity(under);
            helper.assertFalse(HailDamage.exposed(level, under), "a pig under the ship is out of the hail");
            under.moveTo(box.maxX() + 2.5, under.getY(), cz);
            helper.assertTrue(HailDamage.exposed(level, under), "beside it, it isn't: feet " + under.getY()
                    + ", roof " + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    under.getBlockX(), under.getBlockZ()) + ", ship over " + ShipCover.covered(level, under.getX(),
                    under.getY() + 0.5, under.getZ()) + ", at " + under.blockPosition() + " box " + box);
            under.discard();
            remove(level, ship);
            helper.succeed();
        });
    }

    /** Snow settles on a deck and is stripped off at speed; glaze settles too and stays. */
    @GameTest(template = SCRATCH, timeoutTicks = 60)
    public static void deckCollectsSnowAndGlaze(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        openSky(helper);
        ServerSubLevel ship = spawnBlock(helper, 4.5, 7, 4.5, Blocks.STONE.defaultBlockState());
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        helper.runAfterDelay(3, () -> {
            BoundingBox3ic box = ship.getPlot().getBoundingBox();
            BlockPos deck = new BlockPos(box.minX(), box.minY(), box.minZ()).above();
            LocalWeather.Here snow = new LocalWeather.Here(1, false, Precip.SNOW, 1, null, "test");
            ShipWeather.visitShip(level, container, ship, RandomSource.create(5), snow, -5, 6);
            BlockState s = level.getBlockState(deck);
            helper.assertTrue(s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) >= 1, "snow on the deck: " + s);
            ShipWeather.strip(level, ship.getPlot().getBoundingBox());
            helper.assertTrue(level.getBlockState(deck).isAir(), "stripped: " + level.getBlockState(deck));
            LocalWeather.Here ice = new LocalWeather.Here(1, false, Precip.FREEZING_RAIN, 1, null, "test");
            ShipWeather.visitShip(level, container, ship, RandomSource.create(6), ice, -5, 6);
            s = level.getBlockState(deck);
            helper.assertTrue(s.getBlock() instanceof GlazeBlock, "glaze on the deck: " + s);
            ShipWeather.strip(level, ship.getPlot().getBoundingBox());
            helper.assertTrue(level.getBlockState(deck).getBlock() instanceof GlazeBlock, "glaze isn't stripped");
            remove(level, ship);
            helper.succeed();
        });
    }

    /** Opens the test framework's barrier roof over the whole test area. */
    static void openSky(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = -1; x <= 9; x++) {
            for (int z = -1; z <= 9; z++) {
                BlockPos base = helper.absolutePos(new BlockPos(x, 0, z));
                for (int y = 1; y <= 24; y++) {
                    BlockPos p = base.above(y);
                    if (level.getBlockState(p).is(Blocks.BARRIER)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    /** A one-block ship at (x, y, z) relative to the test (as WindGameTests spawns them). */
    static ServerSubLevel spawnBlock(GameTestHelper helper, double x, double y, double z, BlockState state) {
        ServerLevel level = helper.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        Pose3d pose = new Pose3d();
        pose.position().set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
        SubLevel subLevel = container.allocateNewSubLevel(pose);
        LevelPlot plot = subLevel.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, state, 3);
        subLevel.updateLastPose();
        return (ServerSubLevel) subLevel;
    }

    static void remove(ServerLevel level, ServerSubLevel ship) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        LevelPlot plot = ship.getPlot();
        Vector2i origin = container.getOrigin();
        container.removeSubLevel(plot.plotPos.x - origin.x, plot.plotPos.z - origin.y, SubLevelRemovalReason.REMOVED);
    }

    private ShipWeatherGameTests() {
    }
}
