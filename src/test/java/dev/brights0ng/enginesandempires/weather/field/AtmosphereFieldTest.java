package dev.brights0ng.enginesandempires.weather.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;

/** Carrying air, getting back to normal, moisture, rain shadows and air masses. */
class AtmosphereFieldTest {

    /** A flat land world at 10 C, humidity 0.5, with a set wind and an optional water strip and mountain ridge. */
    private static final class World implements FieldEnv {
        double windX;
        double waterBelowX = Double.NEGATIVE_INFINITY;

        @Override
        public double[] climate(double x, double z) {
            return new double[]{10, 0.5, x < waterBelowX ? 2 : 0};
        }

        @Override
        public double[] wind(double x, double z) {
            return new double[]{windX, 0, windX, 0};
        }

        @Override
        public double[] contact(double x, double z) {
            return new double[]{0, 0};
        }

        @Override
        public double heightCorrection(double elevation) {
            return -0.0325 * elevation;
        }
    }

    private static void run(AtmosphereField f, World w, int steps, long dt) {
        for (int i = 0; i < steps; i++) {
            f.step(dt, w);
        }
    }

    @Test
    void capacityGrowsWithWarmth() {
        assertEquals(10, Moisture.capacity(0), 1e-9);
        assertTrue(Moisture.capacity(20) > 3 * Moisture.capacity(0));
        assertTrue(Moisture.capacity(-15) < 5);
    }

    @Test
    void calmAirStaysAtNormal() {
        World w = new World();
        AtmosphereField f = new AtmosphereField();
        f.ensure(0, 0, 4000, w, 0);
        run(f, w, 50, 1000);
        assertEquals(10, f.sample(AtmosphereField.Var.T, 1000, 1000, w), 1e-6);
        assertEquals(0, f.sample(AtmosphereField.Var.ANOMALY, 1000, 1000, w), 1e-6);
        double eq = Moisture.capacity(10) * Moisture.targetHumidity(0, 0.5, 10);
        assertEquals(eq, f.sample(AtmosphereField.Var.Q, 1000, 1000, w), 1e-3);
    }

    /** Weather phase 8a (2026-10-14): real winters have higher relative humidity than summers. */
    @Test
    void coldAirSitsNearerSaturation() {
        double summer = Moisture.targetHumidity(0, 0.4, 25);
        double winter = Moisture.targetHumidity(0, 0.4, -5);
        assertEquals(Moisture.targetHumidity(0, 0.4), summer, 1e-9, "warm air: the ground's own equilibrium");
        assertTrue(winter > summer + 0.2 && winter < 0.8, "temperate land in winter about 65-70%: " + winter);
        assertTrue(Moisture.targetHumidity(2, 0.5, -5) < 1, "never past saturation");
        // Cold air over warmer water takes up water faster; land doesn't care.
        assertEquals(Moisture.evaporationTicks(2) / 3, Moisture.evaporationTicks(2, 0, 8), 1e-6);
        assertEquals(Moisture.evaporationTicks(2), Moisture.evaporationTicks(2, 10, 8), 1e-6, "warm air over cooler water");
        assertEquals(Moisture.evaporationTicks(0), Moisture.evaporationTicks(0, 0, 8), 1e-6);
    }

    /** Weather phase 8b (2026-10-14): moist, still air dries back toward its ground's equilibrium over days. */
    @Test
    void moistStillAirDriesBackTowardEquilibrium() {
        World w = new World();
        AtmosphereField f = new AtmosphereField();
        f.ensure(0, 0, 4000, w, 0);
        double eq = Moisture.capacity(10) * Moisture.targetHumidity(0, 0.5, 10);
        for (FieldTile tile : f.tiles().values()) {
            java.util.Arrays.fill(tile.q, (float) (0.98 * Moisture.capacity(10)));
        }
        double start = f.sample(AtmosphereField.Var.Q, 0, 0, w);
        run(f, w, 72, 1000);
        double after = f.sample(AtmosphereField.Var.Q, 0, 0, w);
        // Three days: about two thirds of the way back.
        assertTrue(after < start - 0.5 * (start - eq), "dried: " + start + " -> " + after + " (eq " + eq + ")");
        assertTrue(after > eq, "but not past its equilibrium");
    }

    /** Weather phase 8b (2026-10-14): a tropical sea's dry season lowers its pull on the air; other seas don't move. */
    @Test
    void aTropicalSeasDrySeasonDriesItsAir() {
        assertEquals(0.85, Moisture.targetHumidity(2, 0.5), 1e-9, "an ordinary sea");
        assertEquals(0.85, Moisture.targetHumidity(2, 0.72), 1e-9, "a tropical sea in the wet season stays at 85%");
        assertEquals(0.63, Moisture.targetHumidity(2, 0.28), 1e-9, "and in the dry season drops");
    }

    @Test
    void outsideTheTilesIsTheClimatesNormal() {
        World w = new World();
        AtmosphereField f = new AtmosphereField();
        assertEquals(10, f.sample(AtmosphereField.Var.T, 1e6, 1e6, w), 1e-9);
    }

    @Test
    void windCarriesAWarmPatchDownwind() {
        World w = new World();
        w.windX = 10;
        AtmosphereField f = new AtmosphereField();
        f.ensure(0, 0, 6000, w, 0);
        FieldTile tile = f.tile(0, 0);
        tile.t[5 * AtmosphereField.SIZE + 5] = 20; // a warm cell at x, z ~ 2816
        double x = tile.cellX(5);
        double z = tile.cellZ(5);
        // 10 m/s carries air 4 blocks a second: one cell (512 blocks) in 128 s = 2560 ticks.
        run(f, w, 26, 100);
        double ahead = f.sample(AtmosphereField.Var.T, x + 512, z, w);
        double behind = f.sample(AtmosphereField.Var.T, x - 512, z, w);
        assertTrue(ahead > behind + 0.5, "the warmth moved east: " + ahead + " vs " + behind);
    }

    @Test
    void anomaliesFadeBackToNormalOverDays() {
        World w = new World();
        AtmosphereField f = new AtmosphereField();
        f.ensure(0, 0, 4000, w, 0);
        FieldTile tile = f.tile(0, 0);
        java.util.Arrays.fill(tile.t, 0f);
        run(f, w, 12, 1000);
        double halfDay = f.sample(AtmosphereField.Var.T, tile.cellX(8), tile.cellZ(8), w);
        run(f, w, 96, 1000);
        double days = f.sample(AtmosphereField.Var.T, tile.cellX(8), tile.cellZ(8), w);
        assertTrue(halfDay < 6, "still cold after half a day: " + halfDay);
        assertTrue(days > 9, "back to normal after a few days: " + days);
    }

    @Test
    void openWaterMoistensTheAir() {
        World w = new World();
        w.waterBelowX = 0;
        AtmosphereField f = new AtmosphereField();
        f.ensure(0, 0, 8000, w, 0);
        run(f, w, 48, 1000);
        double sea = f.sample(AtmosphereField.Var.Q, -4000, 0, w);
        double land = f.sample(AtmosphereField.Var.Q, 4000, 0, w);
        assertTrue(sea > land * 1.3, "wetter over the sea: " + sea + " vs " + land);
    }

    @Test
    void aMountainRidgeCastsARainShadow() {
        World w = new World();
        w.windX = 8;
        w.waterBelowX = -3000; // moist air coming in off a sea to the west
        AtmosphereField f = new AtmosphereField();
        f.ensure(0, 0, 9000, w, 0);
        // A 400-block ridge (13 C colder at its top) running north-south at x ~ 0.
        for (FieldTile tile : f.tiles().values()) {
            for (int k = 0; k < AtmosphereField.SIZE; k++) {
                for (int i = 0; i < AtmosphereField.SIZE; i++) {
                    int idx = k * AtmosphereField.SIZE + i;
                    tile.elevation[idx] = Math.abs(tile.cellX(i)) < 800 ? 400 : 0;
                }
            }
        }
        // 8 m/s carries air about 3 blocks a second: 200 steps of 500 ticks is about 16000 blocks, plenty to settle.
        run(f, w, 200, 500);
        double windward = f.sample(AtmosphereField.Var.Q, -2500, 0, w);
        double lee = f.sample(AtmosphereField.Var.Q, 2500, 0, w);
        // The rain falls where the air first climbs: the ridge's windward (western) cell, centred at x = -768.
        double rainOnRidge = f.sample(AtmosphereField.Var.P, -768, 256, w);
        double rainOnLee = f.sample(AtmosphereField.Var.P, 4000, 0, w);
        assertTrue(lee < windward * 0.9, "drier in the lee: " + lee + " vs " + windward);
        assertTrue(rainOnRidge > rainOnLee + 0.1, "rain wrung out on the ridge: " + rainOnRidge + " vs " + rainOnLee);
    }

    @Test
    void coldAirWrapsBehindTheColdFrontAndTheWarmSectorIsWarm() {
        long lifetime = 100_000;
        WeatherSystem low = new WeatherSystem(1, WeatherSystem.Kind.LOW, 0, 0, 0, 1, 45_000, lifetime, 25, 4000, false);
        AirMassContact c = AirMassContact.prepare(List.of(low), -0.5);
        double[] behind = c.at(-3000, 1500);   // west-southwest: behind the cold front
        double[] sector = c.at(2500, 2500);    // southeast: between the fronts
        assertTrue(behind[0] < -3, "cold behind the cold front: " + behind[0]);
        assertTrue(sector[0] > 2, "warm in the warm sector: " + sector[0]);
        assertTrue(behind[1] > 0.2 && sector[1] > 0.2, "both pushed noticeably");

        WeatherSystem mirrored = new WeatherSystem(2, WeatherSystem.Kind.LOW, 0, 0, 1, -1, 45_000, lifetime, 25, 4000,
                false);
        double[] mSector = AirMassContact.prepare(List.of(mirrored), -0.5).at(2500, -2500);
        assertTrue(mSector[0] > 2, "a mirrored low's warm sector is to the northeast: " + mSector[0]);
    }

    @Test
    void blockingHighsBringHeatInSummerAndColdInWinter() {
        WeatherSystem block = new WeatherSystem(3, WeatherSystem.Kind.HIGH, 0, 0, 0, 1, 50_000, 100_000, 18, 7000, true);
        assertTrue(AirMassContact.prepare(List.of(block), 1).at(0, 0)[0] > 3, "summer heat wave");
        assertTrue(AirMassContact.prepare(List.of(block), -1).at(0, 0)[0] < -3, "winter cold snap");
    }

    /**
     * Weather phase 7c (2026-10-09): stepping in hour-long chunks ({@code /eae weather step}, the night skip) must not
     * also "catch up" every tile by the same hour, or the air settles toward normal twice as fast as in live play.
     */
    @Test
    void hourLongStepsDoNotCatchUpTheHourTheyStep() {
        World w = new World();
        AtmosphereField stepped = new AtmosphereField();
        stepped.ensure(0, 0, 4000, w, 0);
        for (FieldTile tile : stepped.tiles().values()) {
            java.util.Arrays.fill(tile.t, 18f);
            java.util.Arrays.fill(tile.q, 3f);
            tile.lastActive = 1;
        }
        AtmosphereField plain = stepped.copyDomain(-8000, -8000, 8000, 8000);
        for (long time = 1001; time <= 12_001; time += 1000) {
            stepped.touch(0, 0, 4000, time, 1000);
            for (FieldTile tile : stepped.active(time)) {
                stepped.stepTile(tile, 1000, w);
            }
            plain.step(1000, w);
        }
        double a = stepped.sample(AtmosphereField.Var.T, 0, 0, w);
        double b = plain.sample(AtmosphereField.Var.T, 0, 0, w);
        assertEquals(b, a, 0.05, "touched-and-stepped hourly matches plain hourly steps");
        assertEquals(plain.sample(AtmosphereField.Var.Q, 0, 0, w), stepped.sample(AtmosphereField.Var.Q, 0, 0, w), 0.05);

        // A tile left alone for half a day still catches up when it comes back.
        AtmosphereField away = plain.copyDomain(-8000, -8000, 8000, 8000);
        for (FieldTile tile : away.tiles().values()) {
            tile.lastActive = 12_001;
        }
        double before = away.sample(AtmosphereField.Var.T, 0, 0, w);
        away.touch(0, 0, 4000, 24_001, 100);
        double after = away.sample(AtmosphereField.Var.T, 0, 0, w);
        assertTrue(Math.abs(after - 10) < Math.abs(before - 10) - 0.5, "it relaxed toward normal: " + before + " -> "
                + after);
    }

    /** Weather phase 7c (2026-10-09): the air mixes with its neighbours at the same rate whatever the step length. */
    @Test
    void mixingDoesNotDependOnTheStepLength() {
        World w = new World();
        AtmosphereField base = new AtmosphereField();
        base.ensure(0, 0, 4000, w, 0);
        // A sharp warm spike in still air: only mixing spreads it.
        for (FieldTile tile : base.tiles().values()) {
            for (int i = 0; i < tile.t.length; i++) {
                if (Math.abs(tile.cellX(i % AtmosphereField.SIZE)) < 300 && Math.abs(tile.cellZ(i / AtmosphereField.SIZE)) < 300) {
                    tile.t[i] = 30f;
                }
            }
        }
        AtmosphereField fine = base.copyDomain(-8000, -8000, 8000, 8000);
        AtmosphereField coarse = base.copyDomain(-8000, -8000, 8000, 8000);
        run(fine, w, 60, 100);
        run(coarse, w, 6, 1000);
        double a = fine.sample(AtmosphereField.Var.T, 256, 256, w);
        double b = coarse.sample(AtmosphereField.Var.T, 256, 256, w);
        double spreadFine = fine.sample(AtmosphereField.Var.T, 1280, 256, w);
        double spreadCoarse = coarse.sample(AtmosphereField.Var.T, 1280, 256, w);
        assertEquals(a, b, 1.0, "the spike decays alike: " + a + " vs " + b);
        assertEquals(spreadFine, spreadCoarse, 0.6, "and spreads alike: " + spreadFine + " vs " + spreadCoarse);
    }

    /** 8b follow-up (2026-10-14): a peak meets the climb from its windward side; centred slopes cancelled there. */
    @Test
    void theColdBoostStopsShortOfSaturation() {
        double land = Moisture.targetHumidity(0, 0.5, -5);
        assertEquals(0.5 + 0.45 * 0.5, land, 1e-9, "dry land still gets its full winter boost");
        assertEquals(Moisture.COLD_BOOST_CAP, Moisture.targetHumidity(1, 0.9, -5), 1e-9, "moist forest stops at the cap");
        assertEquals(Moisture.COLD_BOOST_CAP, Moisture.targetHumidity(2, 0.5, 0), 1e-9, "so does the sea");
        assertEquals(0.45 + 0.45, Moisture.targetHumidity(1, 1, -10), 1e-9, "ground already above it keeps its own");
    }

    /** 8b follow-up (2026-10-14): a peak meets the climb from its windward side; centred slopes cancelled there. */
    @Test
    void aPeakMeetsTheWindsClimb() {
        AtmosphereField field = new AtmosphereField();
        FieldTile tile = new FieldTile(0, 0);
        java.util.Arrays.fill(tile.elevation, 0f);
        // A one-cell peak 120 blocks high at cell (5, 5).
        tile.elevation[5 * AtmosphereField.SIZE + 5] = 120f;
        field.put(tile);
        double x = tile.cellX(5);
        double z = tile.cellZ(5);
        double[] east = field.slopeFacing(x, z, 8, 0);
        assertEquals(120.0 / AtmosphereField.CELL, east[0], 1e-9, "a westerly climbs the peak's west face");
        assertEquals(0, east[1], 1e-9);
        double[] lee = field.slopeFacing(tile.cellX(6), z, 8, 0);
        assertTrue(8 * lee[0] < 0, "the cell downwind is on the descent");
        double[] calm = field.slopeFacing(x, z, 0.1, 0);
        assertEquals(0, calm[0], 1e-9, "in a calm, the centred slope (zero on a peak)");
    }

    /**
     * 8b follow-up round 6: the climb is read from the fine terrain along the wind: a narrow 150-block ridge inside one
     * cell (invisible to cell averages) still lifts the air its full height.
     */
    @Test
    void theClimbComesFromTheRealTerrain() {
        AtmosphereField field = new AtmosphereField();
        FieldTile tile = new FieldTile(0, 0);
        java.util.Arrays.fill(tile.elevation, 10f);
        java.util.Arrays.fill(tile.fine, 10f);
        int side = AtmosphereField.SIZE * AtmosphereField.FINE_PER_CELL;
        // A ridge one fine sample wide along z at fine column 30, rising 150 blocks.
        for (int k = 0; k < side; k++) {
            tile.fine[k * side + 30] = 160f;
        }
        field.put(tile);
        double x = tile.fineX(30);
        double z = tile.fineZ(20);
        assertEquals(150, field.climbFacing(x, z, 6, 0), 1e-9, "a westerly climbs the whole ridge");
        assertEquals(0, field.climbFacing(tile.fineX(31), z, 6, 0), 1e-9, "and none on the lee side");
        double[] slope = field.slopeFacing(x, z, 6, 0);
        assertTrue(slope[0] > 0.5, "steep on the fine terrain: " + slope[0]);
        assertTrue(Double.isNaN(field.climbFacing(x, z, 0.1, 0)), "no wind, no climb");
        java.util.Arrays.fill(tile.fine, Float.NaN);
        assertTrue(Double.isNaN(field.climbFacing(x, z, 6, 0)), "unknown until the fine terrain is worked out");
    }
}
