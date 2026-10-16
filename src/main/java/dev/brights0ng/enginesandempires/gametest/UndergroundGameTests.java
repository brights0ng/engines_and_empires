package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.weather.climate.Climate;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.climate.Underground;
import dev.brights0ng.enginesandempires.weather.climate.UndergroundProbe;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8c: underground temperature in the world. The test world is superflat, so real depth is supplied to
 * {@link UndergroundProbe#read(ServerLevel, BlockPos, double, int)} where a test needs it; what is tested here is the
 * world's part (sky light, builds not counting as cover, the hook in {@link Temperature#at}).
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class UndergroundGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    /** A sealed stone box from (1,1,1) to (5,5,5); its inside centre is returned. */
    private static BlockPos sealedBox(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) {
            for (int y = 1; y <= 5; y++) {
                for (int z = 1; z <= 5; z++) {
                    boolean shell = x == 1 || x == 5 || y == 1 || y == 5 || z == 1 || z == 5;
                    helper.setBlock(new BlockPos(x, y, z), shell ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
        return helper.absolutePos(new BlockPos(3, 2, 3));
    }

    private static double outside(ServerLevel level, BlockPos pos) {
        return Climate.sample(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5).temperature();
    }

    @GameTest(template = SCRATCH)
    public static void theOpenAirIsUnchanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos open = helper.absolutePos(new BlockPos(3, 40, 3));
        var r = Temperature.reading(level, open);
        helper.assertTrue(r.insulation() == 0, "open sky: no insulation, " + r);
        helper.assertTrue(Math.abs(r.temperature() - outside(level, open)) < 1e-6, "outside air unchanged: " + r);
        helper.succeed();
    }

    @GameTest(template = SCRATCH, timeoutTicks = 60)
    public static void aBuildAddsNoCover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inside = sealedBox(helper);
        helper.runAfterDelay(20, () -> {
            var r = Temperature.reading(level, inside);
            helper.assertTrue(level.getBrightness(net.minecraft.world.level.LightLayer.SKY, inside) == 0,
                    "the box is dark to the sky");
            // The generator's ground is below the box: a room you built is a room (phase 8f), not a cave.
            helper.assertTrue(r.insulation() == 0, "a sealed build is not underground: " + r);
            helper.succeed();
        });
    }

    @GameTest(template = SCRATCH, timeoutTicks = 60)
    public static void deepUnderCoverIsTheRocksTemperature(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inside = sealedBox(helper);
        helper.runAfterDelay(20, () -> {
            double outside = outside(level, inside);
            int ground = inside.getY() + 80;
            var r = UndergroundProbe.read(level, inside, outside, ground);
            helper.assertTrue(r.insulation() > 0.999, "80 blocks of cover and no sky: " + r);
            var s = dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.underground();
            if (r.cave() == null && inside.getY() < s.bandBottom()) {
                // The superflat test floor sits far below y0: the rock's gradient, give or take its texture.
                double rock = Underground.rock(inside.getY() + 0.5, level.getMinBuildHeight(), s);
                helper.assertTrue(Math.abs(r.temperature() - rock) <= s.noiseAmp() + 1e-6, "the rock's gradient: "
                        + r);
                helper.assertTrue(r.pull() == 1, "full pull below the band: " + r);
            }
            helper.succeed();
        });
    }

    @GameTest(template = SCRATCH)
    public static void theSkyCancelsCover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos open = helper.absolutePos(new BlockPos(3, 40, 3));
        double outside = outside(level, open);
        // As if at the bottom of a deep ravine with the sky in full view.
        var r = UndergroundProbe.read(level, open, outside, open.getY() + 60);
        helper.assertTrue(r.skylight() == 15 && r.insulation() == 0, "sky in full view: outside air, " + r);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void theOpenAirKeepsItsHumidity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos open = helper.absolutePos(new BlockPos(3, 40, 3));
        helper.assertTrue(UndergroundProbe.humidity(level, open, 0.42) == 0.42, "open sky: the outside humidity");
        // Cover with the sky in full view is outside air too.
        helper.assertTrue(UndergroundProbe.humidity(level, open, 0.42, open.getY() + 60) == 0.42,
                "a ravine's floor: the outside humidity");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, timeoutTicks = 60)
    public static void aBuildKeepsTheOutsideHumidity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inside = sealedBox(helper);
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(UndergroundProbe.humidity(level, inside, 0.42) == 0.42,
                    "a sealed build is a room, not a cave: the outside humidity");
            helper.succeed();
        });
    }

    @GameTest(template = SCRATCH, timeoutTicks = 60)
    public static void deepUnderCoverIsTheFlatHumidity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inside = sealedBox(helper);
        helper.runAfterDelay(20, () -> {
            var s = dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.underground();
            var cave = UndergroundProbe.caveAt(level, inside, s);
            double expected = cave != null && cave.hasHumidity() ? cave.humidity() : s.humidity();
            // The same under a dry sky and a wet one: what is overhead does not matter.
            for (double outside : new double[] {0.1, 0.95}) {
                double rh = UndergroundProbe.humidity(level, inside, outside, inside.getY() + 80);
                helper.assertTrue(Math.abs(rh - expected) < 1e-9, "80 blocks of cover and no sky, outside " + outside
                        + ": " + rh + " vs " + expected);
            }
            helper.succeed();
        });
    }
}
