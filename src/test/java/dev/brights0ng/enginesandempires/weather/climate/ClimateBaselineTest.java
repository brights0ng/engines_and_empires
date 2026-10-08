package dev.brights0ng.enginesandempires.weather.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.climate.BiomeClimate.Surface;

/** The regional field's smoothing and the baseline's blend, seasons and frozen clamp. */
class ClimateBaselineTest {

    private static final BiomeClimate PLAINS = new BiomeClimate(11, 12, 0.5, Surface.LAND, false);
    private static final BiomeClimate SNOWY = new BiomeClimate(-10, 15, 0.4, Surface.LAND, false);
    private static final BiomeClimate FROZEN = new BiomeClimate(-8, 6, 0.55, Surface.ICE, true);

    @Test
    void aUniformWorldStaysUniform() {
        ClimateField field = new ClimateField((x, z) -> PLAINS);
        ClimateField.Regional r = field.regional(1234.5, -777.25);
        assertEquals(11, r.mean(), 1e-9);
        assertEquals(12, r.swing(), 1e-9);
        assertEquals(0.5, r.humidity(), 1e-9);
    }

    @Test
    void aBiomeBorderIsSmoothedOverAboutAKilometre() {
        // Plains west of x = 0, snowy plains east.
        ClimateField field = new ClimateField((x, z) -> x < 0 ? PLAINS : SNOWY);
        double atBorder = field.regional(0, 0).mean();
        assertTrue(atBorder < 11 && atBorder > -10, "a blend at the border: " + atBorder);
        assertEquals(11, field.regional(-1200, 0).mean(), 0.5, "far west is plains");
        assertEquals(-10, field.regional(1200, 0).mean(), 0.5, "far east is snowy");
        // No steps between nodes.
        double a = field.regional(63.9, 0).mean();
        double b = field.regional(64.1, 0).mean();
        assertEquals(a, b, 0.05);
    }

    @Test
    void aSmallSnowyPatchIsColderThanItsSurroundingsButNotAsColdAsASnowyRegion() {
        // One snowy node in a sea of plains.
        ClimateField field = new ClimateField((x, z) -> x == 0 && z == 0 ? SNOWY : PLAINS);
        Baseline.Sample patch = Baseline.combine(field.regional(0, 0), SNOWY, 0.5, 0, 0, 0, 0);
        Baseline.Sample around = Baseline.combine(field.regional(2000, 0), PLAINS, 0.5, 0, 0, 0, 0);
        ClimateField snowyWorld = new ClimateField((x, z) -> SNOWY);
        Baseline.Sample region = Baseline.combine(snowyWorld.regional(0, 0), SNOWY, 0.5, 0, 0, 0, 0);
        assertTrue(patch.temperature() < around.temperature() - 5, patch + " vs " + around);
        assertTrue(patch.temperature() > region.temperature() + 5, patch + " vs " + region);
    }

    @Test
    void seasonsDayAndHeightAddUp() {
        ClimateField.Regional r = new ClimateField.Regional(11, 12, 0.5);
        Baseline.Sample s = Baseline.combine(r, PLAINS, 0.5, 1, 2, 1, -3);
        // 11 mean + 12 summer + 2 band + 6 afternoon (range 6 at humidity 0.5) - 3 height.
        assertEquals(28, s.temperature(), 1e-9);
        assertEquals(22, s.dailyMean(), 1e-9);
        Baseline.Sample winterDawn = Baseline.combine(r, PLAINS, 0.5, -1, 0, -1, 0);
        assertEquals(11 - 12 - 6, winterDawn.temperature(), 1e-9);
    }

    @Test
    void coldBiomesThawInSummerButFrozenOnesNever() {
        ClimateField.Regional snowyRegion = new ClimateField.Regional(-10, 15, 0.4);
        Baseline.Sample snowySummer = Baseline.combine(snowyRegion, SNOWY, 0.5, 1, 0, 1, 0);
        assertTrue(snowySummer.temperature() > 0, "snowy plains thaw on a summer afternoon");
        ClimateField.Regional warmRegion = new ClimateField.Regional(20, 10, 0.5);
        Baseline.Sample frozenSummer = Baseline.combine(warmRegion, FROZEN, 0.5, 1, 10, 1, 5);
        assertEquals(BiomeClimate.FROZEN_MAX, frozenSummer.temperature(), 1e-9, "frozen whatever the rest says");
        assertTrue(frozenSummer.clamped());
        Baseline.Sample frozenWinter = Baseline.combine(snowyRegion, FROZEN, 0.5, -1, 0, -1, 0);
        assertFalse(frozenWinter.clamped(), "already colder than the clamp");
    }

    @Test
    void fallbackFollowsVanillaTemperatureAndDownfall() {
        BiomeClimate warmWet = BiomeClimate.fallback(0.95f, 0.9f, Surface.FOREST);
        assertEquals(16, warmWet.mean(), 1e-5);
        assertTrue(warmWet.swing() < 8, "humid places swing less");
        BiomeClimate dry = BiomeClimate.fallback(2.0f, 0f, Surface.LAND);
        assertEquals(14, dry.swing(), 1e-9);
        assertFalse(dry.frozen());
    }
}
