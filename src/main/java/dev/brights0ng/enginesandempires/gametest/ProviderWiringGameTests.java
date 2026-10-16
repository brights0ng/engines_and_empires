package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.frontier.incursion.Incursion;
import dev.brights0ng.frontier.incursion.Incursions;
import dev.brights0ng.frontier.incursion.ThumperAccess;
import dev.brights0ng.meteodyne.WeatherOwnership;
import dev.brights0ng.meteodyne.climate.Baseline;
import dev.brights0ng.meteodyne.climate.Climate;
import dev.brights0ng.meteodyne.climate.MeteodyneTemperature;
import dev.brights0ng.meteodyne.climate.Temperature;
import dev.brights0ng.meteodyne.climate.UndergroundProbe;
import dev.brights0ng.meteodyne.heat.world.HeatWorld;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.meteodyne.sky.MeteodyneStorms;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.services.StormProvider;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import dev.brights0ng.paradynelib.services.WindProvider;
import dev.brights0ng.paradynelib.ships.ShipCover;
import dev.brights0ng.seismicsurveyance.SeismicContent;
import dev.brights0ng.simplespoilage.FoodTemperature;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The pack's mods answering each other's questions (split seams S1-S3, S6): Meteodyne registers the library's
 * temperature, wind and storm providers, Frontier gets Seismic Surveyance's thumpers, and food sees the same temperature
 * it saw before the split, when Simple Spoilage read Meteodyne's classes itself.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class ProviderWiringGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final BlockPos SPOT = new BlockPos(3, 1, 3);
    private static final BlockPos PLATE = new BlockPos(3, 1, 3);
    private static final BlockPos THUMPER = new BlockPos(3, 2, 3);

    @GameTest(template = SCRATCH, batch = "provider_wiring")
    public static void meteodyneAnswersTheLibrarysQuestions(GameTestHelper helper) {
        helper.assertTrue(TemperatureProvider.get() == MeteodyneTemperature.INSTANCE, "temperatures come from Meteodyne");
        helper.assertTrue(WindProvider.get() == WindSources.PROVIDER, "the wind comes from Meteodyne");
        helper.assertTrue(StormProvider.get() == MeteodyneStorms.INSTANCE, "storms come from Meteodyne");
        helper.assertTrue(ThumperAccess.get() != ThumperAccess.NONE, "Frontier knows Seismic Surveyance's thumpers");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "provider_wiring_food")
    public static void foodSeesTheTemperatureItSawBeforeTheSplit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FoodTemperature.clearOverride();
        for (BlockPos spot : new BlockPos[]{SPOT, SPOT.below(4), SPOT.above(40)}) {
            BlockPos at = helper.absolutePos(spot);
            same(helper, "at " + spot, before(level, at), FoodTemperature.at(level, at));
            same(helper, "seasonal at " + spot, beforeSeasonal(level, at), FoodTemperature.seasonal(level, at));
        }
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether != null) {
            helper.assertTrue(Double.isNaN(FoodTemperature.at(nether, BlockPos.ZERO)), "no temperature in the Nether");
            helper.assertTrue(Double.isNaN(FoodTemperature.seasonal(nether, BlockPos.ZERO)), "nor a seasonal one");
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "provider_wiring_thumpers")
    public static void aThumperIncursionGoesAfterSeismicsThumpers(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(PLATE, SeismicContent.STRIKE_PLATE.get());
        helper.setBlock(THUMPER, SeismicContent.MECHANICAL_THUMPER.get());
        helper.assertTrue(ThumperAccess.get().isThumper(helper.getBlockState(THUMPER)), "a mechanical thumper is a thumper");
        Incursions incursions = Incursions.of(helper.getLevel());
        Incursion incursion = incursions.startThumper(helper.absolutePos(THUMPER));
        try {
            helper.assertTrue(incursion != null, "a thumper incursion starts");
            helper.assertTrue(incursion.thumpers().contains(helper.absolutePos(THUMPER)), "against the thumper there: "
                    + incursion.thumpers());
        } finally {
            incursions.stopAll();
        }
        helper.succeed();
    }

    // Simple Spoilage's FoodTemperature as it was before split seam S1, reading Meteodyne's classes itself.

    private static double before(ServerLevel level, BlockPos pos) {
        if (!WeatherOwnership.owns(level)) {
            return Double.NaN;
        }
        double air;
        if (ShipCover.inPlot(level, pos)) {
            Vec3 w = ShipCover.worldCentre(level, pos);
            if (w == null) {
                return Double.NaN;
            }
            air = Climate.sample(level, w.x, w.y, w.z).temperature();
        } else {
            air = Temperature.at(level, pos);
        }
        return air + room(level, Vec3.atCenterOf(pos));
    }

    private static double beforeSeasonal(ServerLevel level, BlockPos pos) {
        if (!WeatherOwnership.owns(level)) {
            return Double.NaN;
        }
        double t;
        if (ShipCover.inPlot(level, pos)) {
            Vec3 w = ShipCover.worldCentre(level, pos);
            if (w == null) {
                return Double.NaN;
            }
            t = mean(Climate.sample(level, w.x, w.y, w.z));
        } else {
            double outside = mean(Climate.sample(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            t = UndergroundProbe.read(level, pos, outside).temperature();
        }
        return t + room(level, Vec3.atCenterOf(pos));
    }

    private static double mean(Baseline.Sample s) {
        return s.dailyMean() - s.anomaly();
    }

    private static double room(ServerLevel level, Vec3 at) {
        HeatWorld heat = HeatWorld.of(level);
        return heat != null && WeatherConfig.heat().enabled() ? heat.roomAt(at) : 0;
    }

    private static void same(GameTestHelper helper, String what, double expected, double actual) {
        helper.assertTrue(Double.isNaN(expected) ? Double.isNaN(actual) : Double.compare(expected, actual) == 0,
                what + ": expected " + expected + ", got " + actual);
    }

    private ProviderWiringGameTests() {
    }
}
