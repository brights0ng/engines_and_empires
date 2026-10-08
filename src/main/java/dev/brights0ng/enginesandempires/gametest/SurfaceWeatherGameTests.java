package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.surface.SurfaceWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Phase 5b of the weather backbone: snow and ice on the ground ({@link SurfaceWeather}), driven column by column with
 * the weather given, so the outcome doesn't depend on the test world's own weather.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class SurfaceWeatherGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final int VISITS = 12;

    private static SurfaceWeather.Column snow(double windX) {
        return new SurfaceWeather.Column(-5, Precip.SNOW, 1, false, windX, 0);
    }

    private static SurfaceWeather.Column dry(double t) {
        return new SurfaceWeather.Column(t, null, 0, false, 0, 0);
    }

    private static void visit(GameTestHelper helper, BlockPos top, SurfaceWeather.Column c, int times) {
        ServerLevel level = helper.getLevel();
        RandomSource random = RandomSource.create(42);
        for (int i = 0; i < times; i++) {
            SurfaceWeather.visit(level, helper.absolutePos(top), c, random);
        }
    }

    private static int layers(GameTestHelper helper, BlockPos pos) {
        BlockState s = helper.getBlockState(pos);
        return s.is(Blocks.SNOW) ? s.getValue(SnowLayerBlock.LAYERS) : 0;
    }

    /** A wall with the wind blowing onto it: 8, 6, 4 layers piled against it, then 2 on open ground. */
    @GameTest(template = SCRATCH)
    public static void snowDriftsAgainstAWallDownwind(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.STONE);
        }
        helper.setBlock(new BlockPos(0, 2, 3), Blocks.STONE);
        for (int x = 1; x < 6; x++) {
            visit(helper, new BlockPos(x, 2, 3), snow(-6), VISITS);
        }
        int[] expected = {8, 6, 4, 2, 2};
        for (int x = 1; x < 6; x++) {
            int got = layers(helper, new BlockPos(x, 2, 3));
            helper.assertTrue(got == expected[x - 1], "drift " + x + " from the wall: " + got + " layers, expected "
                    + expected[x - 1]);
        }
        helper.succeed();
    }

    /** The same wall with the wind blowing away from it: no drift in its lee. */
    @GameTest(template = SCRATCH)
    public static void noDriftInTheLee(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(0, 2, 3), Blocks.STONE);
        visit(helper, new BlockPos(1, 2, 3), snow(6), VISITS);
        int got = layers(helper, new BlockPos(1, 2, 3));
        helper.assertTrue(got == 2, "in the wall's lee, open-ground depth: " + got);
        helper.succeed();
    }

    /** Two layers on leaves, one on the ground under them. */
    @GameTest(template = SCRATCH)
    public static void snowOnAndUnderLeaves(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 4, 3), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        ServerLevel level = helper.getLevel();
        RandomSource random = RandomSource.create(7);
        for (int i = 0; i < VISITS; i++) {
            SurfaceWeather.visit(level, helper.absolutePos(new BlockPos(3, 5, 3)),
                    helper.absolutePos(new BlockPos(3, 2, 3)), snow(0), random);
        }
        helper.assertTrue(layers(helper, new BlockPos(3, 5, 3)) == 2, "2 layers on the leaves");
        helper.assertTrue(layers(helper, new BlockPos(3, 2, 3)) == 1, "1 layer under them");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void snowMeltsWhenItWarms(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4));
        visit(helper, new BlockPos(3, 2, 3), dry(2), 1);
        helper.assertTrue(layers(helper, new BlockPos(3, 2, 3)) >= 3, "slow just above freezing");
        visit(helper, new BlockPos(3, 2, 3), dry(20), 1);
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 2, 3));
        helper.succeed();
    }

    /** A pond freezes right across in a hard freeze and thaws again when it warms. */
    @GameTest(template = SCRATCH)
    public static void aPondFreezesAndThaws(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                boolean rim = x == 1 || x == 5 || z == 1 || z == 5;
                helper.setBlock(new BlockPos(x, 2, z), rim ? Blocks.STONE : Blocks.WATER);
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                visit(helper, new BlockPos(x, 3, z), dry(-12), 1);
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.assertBlockPresent(Blocks.ICE, new BlockPos(x, 2, z));
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                visit(helper, new BlockPos(x, 3, z), dry(8), 1);
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.assertBlockPresent(Blocks.WATER, new BlockPos(x, 2, z));
            }
        }
        helper.succeed();
    }

    /** Ice thaws under glass, stays under a roof; packed ice never thaws. */
    @GameTest(template = SCRATCH)
    public static void iceThawsOnlyWhereItSeesTheSky(GameTestHelper helper) {
        for (int x = 1; x <= 5; x += 2) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.STONE);
        }
        helper.setBlock(new BlockPos(1, 2, 3), Blocks.ICE);
        helper.setBlock(new BlockPos(1, 4, 3), Blocks.GLASS);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.ICE);
        helper.setBlock(new BlockPos(3, 4, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 2, 3), Blocks.PACKED_ICE);
        visit(helper, new BlockPos(1, 5, 3), dry(10), 1);
        visit(helper, new BlockPos(3, 5, 3), dry(10), 1);
        visit(helper, new BlockPos(5, 3, 3), dry(10), 1);
        helper.assertBlockPresent(Blocks.WATER, new BlockPos(1, 2, 3));
        helper.assertBlockPresent(Blocks.ICE, new BlockPos(3, 2, 3));
        helper.assertBlockPresent(Blocks.PACKED_ICE, new BlockPos(5, 2, 3));
        helper.succeed();
    }

    /** Vanilla's cold check answers from the pack's temperature. */
    @GameTest(template = SCRATCH)
    public static void vanillasColdCheckFollowsThePack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (!WeatherOwnership.owns(level)) {
            helper.succeed();
            return;
        }
        for (int dy : new int[]{2, 150}) {
            BlockPos pos = helper.absolutePos(new BlockPos(3, dy, 3));
            double t = SurfaceWeather.temperatureAt(level, pos);
            if (Math.abs(t) < 0.05) {
                continue;
            }
            boolean cold = level.getBiome(pos).value().coldEnoughToSnow(pos);
            helper.assertTrue(cold == (t < 0), "at " + pos.getY() + ": " + t + " C, vanilla says cold " + cold);
        }
        helper.succeed();
    }

    private SurfaceWeatherGameTests() {
    }
}
