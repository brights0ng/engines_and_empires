package dev.brights0ng.enginesandempires.weather.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Seasons, day and night, and the climate bands. */
class ClimateCurvesTest {

    @Test
    void midsummerIsWarmestAndMidwinterColdest() {
        assertEquals(1, ClimateCurves.season(0.375), 1e-9, "mid summer");
        assertEquals(-1, ClimateCurves.season(0.875), 1e-9, "mid winter");
        assertEquals(0, ClimateCurves.season(0.125), 1e-9, "mid spring is the year's mean");
        assertEquals(ClimateCurves.season(0.1), ClimateCurves.season(1.1), 1e-9, "the year wraps");
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
