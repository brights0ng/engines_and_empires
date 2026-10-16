package dev.brights0ng.enginesandempires.weather.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Seasons, day and night, and the climate bands. */
class ClimateCurvesTest {

    @Test
    void midsummerIsWarmestAndMidwinterColdest() {
        // Weather phase 8b: the land's air is warmest a sub-season after the sun (the start of summer).
        assertEquals(1, ClimateCurves.season(1.0 / 3), 1e-9, "warmest one sub-season into summer");
        assertEquals(-1, ClimateCurves.season(5.0 / 6), 1e-9, "coldest one sub-season into winter");
        assertEquals(0, ClimateCurves.season(1.0 / 12), 1e-9, "the year's mean at the end of early spring");
        assertEquals(ClimateCurves.season(0.1), ClimateCurves.season(1.1), 1e-9, "the year wraps");
    }

    /** Weather phase 8b (2026-10-14): the air lags the sun, the sea lags most. */
    @Test
    void theAirLagsTheSunAndTheSeaLagsMost() {
        assertEquals(1, ClimateCurves.sun(0.25), 1e-9, "the sun is highest at the start of summer");
        assertEquals(-1, ClimateCurves.sun(0.75), 1e-9, "lowest at the start of winter");
        assertEquals(1, ClimateCurves.sea(5.0 / 12), 1e-9, "the sea is warmest two sub-seasons later");
        // Mid spring: the sun is well ahead of the land, the land ahead of the sea; mid autumn the reverse.
        assertTrue(ClimateCurves.sun(0.125) > ClimateCurves.season(0.125) + 0.3);
        assertTrue(ClimateCurves.season(0.125) > ClimateCurves.sea(0.125) + 0.2);
        assertTrue(ClimateCurves.sun(0.625) < ClimateCurves.season(0.625) - 0.3);
        assertEquals(0, ClimateCurves.sun(Double.NaN));
    }

    /** Serene Seasons' tropical calendar: wet early winter to late spring, dry early summer to late autumn. */
    @Test
    void theTropicalWetSeasonFollowsSereneSeasons() {
        assertEquals(1, ClimateCurves.tropicalWet(0), 1e-9, "mid wet straddles the turn of the year");
        assertEquals(-1, ClimateCurves.tropicalWet(0.5), 1e-9, "mid dry: late summer into early autumn");
        assertTrue(ClimateCurves.tropicalWet(0.15) > 0, "late spring: still wet");
        assertTrue(ClimateCurves.tropicalWet(0.3) < 0, "early summer: dry");
        assertTrue(ClimateCurves.tropicalWet(0.8) > 0, "early winter: wet");
    }

    @Test
    void theBandsAreStrongerInWinter() {
        assertEquals(1.3, ClimateCurves.bandScale(-1), 1e-9);
        assertEquals(0.7, ClimateCurves.bandScale(1), 1e-9);
        assertEquals(1, ClimateCurves.bandScale(Double.NaN), 1e-9);
    }

    /** Weather phase 8b: tropical humidity swings with the wet and dry seasons; temperate and desert climates don't. */
    @Test
    void tropicalClimatesSwingLessThroughTheYear() {
        BiomeClimate savanna = new BiomeClimate(25, 5, 0.5, BiomeClimate.Surface.LAND, false);
        ClimateField.Regional region = new ClimateField.Regional(25, 5, 0.5);
        double winter = Baseline.combine(region, savanna, 0.5, -1, 0, 0, 0, 0, 0).temperature();
        assertEquals(25 - 5 * (1 - Baseline.TROPICAL_SWING_DAMP), winter, 1e-9, "savanna winter: 23 C, not 20");
        BiomeClimate plains = new BiomeClimate(11, 11, 0.5, BiomeClimate.Surface.LAND, false);
        ClimateField.Regional temperate = new ClimateField.Regional(11, 11, 0.5);
        assertEquals(0, Baseline.combine(temperate, plains, 0.5, -1, 0, 0, 0, 0, 0).temperature(), 1e-9,
                "temperate winter keeps its full swing");
    }

    /** Weather phase 8b: tropical humidity swings with the wet and dry seasons; temperate and desert climates don't. */
    @Test
    void tropicalHumiditySwingsWithTheWetSeason() {
        BiomeClimate savanna = new BiomeClimate(25, 3, 0.5, BiomeClimate.Surface.LAND, false);
        ClimateField.Regional region = new ClimateField.Regional(25, 3, 0.5);
        double wet = Baseline.combine(region, savanna, 0.5, 0, 0, 0, 0, 0, 1).humidity();
        double dry = Baseline.combine(region, savanna, 0.5, 0, 0, 0, 0, 0, -1).humidity();
        assertEquals(0.5 + Baseline.TROPICAL_SWING, wet, 1e-9);
        assertEquals(0.5 - Baseline.TROPICAL_SWING, dry, 1e-9);
        BiomeClimate plains = new BiomeClimate(11, 11, 0.5, BiomeClimate.Surface.LAND, false);
        ClimateField.Regional temperate = new ClimateField.Regional(11, 11, 0.5);
        assertEquals(0.5, Baseline.combine(temperate, plains, 0.5, 0, 0, 0, 0, 0, 1).humidity(), 1e-9);
        assertEquals(0, Baseline.tropicalness(25, 10), 1e-9, "hot deserts swing too much to count");
        assertEquals(1, Baseline.tropicalness(25, 3), 1e-9);
        // A jungle's climate in a cold band (-15 C of band offset) isn't tropical.
        assertEquals(0.5, Baseline.combine(region, savanna, 0.5, 0, -15, 0, 0, 0, 1).humidity(), 1e-9);
    }

    @Test
    void noSeasonsWithoutASeasonSource() {
        assertEquals(0, ClimateCurves.season(Double.NaN));
    }

    @Test
    void coldestAtDawnWarmestMidAfternoon() {
        assertEquals(-1, ClimateCurves.diurnal(0), 1e-9, "6:00");
        assertEquals(1, ClimateCurves.diurnal(9000), 1e-9, "15:00");
        assertEquals(0.5, ClimateCurves.diurnal(6000), 1e-9, "noon is already well on the way up");
        assertTrue(ClimateCurves.diurnal(18000) < 0, "midnight is cooling");
        assertEquals(ClimateCurves.diurnal(3000), ClimateCurves.diurnal(27000), 1e-9, "days repeat");
        // Continuous through dawn: just before and just after 6:00 are both nearly the minimum.
        assertEquals(-1, ClimateCurves.diurnal(23999), 1e-3);
        assertEquals(-1, ClimateCurves.diurnal(1), 1e-3);
    }

    @Test
    void dryAirSwingsMoreThanHumid() {
        assertEquals(9, ClimateCurves.diurnalRange(0), 1e-9, "desert: 18 C dawn to afternoon");
        assertEquals(3, ClimateCurves.diurnalRange(1), 1e-9, "rainforest: 6 C");
    }

    @Test
    void bandsAreTemperateAtSpawnColdNorthWarmSouth() {
        assertEquals(0, ClimateCurves.band(0, 64000, 20), 1e-9);
        assertEquals(-20, ClimateCurves.band(-16000, 64000, 20), 1e-9, "a quarter period north");
        assertEquals(20, ClimateCurves.band(16000, 64000, 20), 1e-9, "a quarter period south");
        assertEquals(ClimateCurves.band(5000, 64000, 20), ClimateCurves.band(69000, 64000, 20), 1e-6, "repeats");
    }
}
