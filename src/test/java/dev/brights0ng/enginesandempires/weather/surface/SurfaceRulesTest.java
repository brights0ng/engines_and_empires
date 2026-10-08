package dev.brights0ng.enginesandempires.weather.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class SurfaceRulesTest {

    @Test
    void heavySnowAddsALayerAnHour() {
        assertEquals(1, SurfaceRules.snowChance(0.8, -5), 1e-9);
        assertEquals(0.5, SurfaceRules.snowChance(0.4, -5), 1e-9);
        assertTrue(SurfaceRules.snowChance(1, 0.5) < 0.6, "wet snow mostly melts as it lands");
        assertEquals(0, SurfaceRules.snowChance(1, 1.5), 1e-9);
    }

    @Test
    void aLongStormBuriesOpenGroundTwoDeep() {
        // Eight hours of heavy snow, one visit an hour, on open ground.
        Random r = new Random(1);
        int layers = 0;
        for (int hour = 0; hour < 8; hour++) {
            if (r.nextDouble() < SurfaceRules.snowChance(0.9, -6) && layers < SurfaceRules.driftCap(0)) {
                layers++;
            }
        }
        assertEquals(2, layers);
    }

    @Test
    void driftsDeepenTowardsTheWallUpwind() {
        assertEquals(8, SurfaceRules.driftCap(1));
        assertEquals(6, SurfaceRules.driftCap(2));
        assertEquals(4, SurfaceRules.driftCap(3));
        assertEquals(2, SurfaceRules.driftCap(4));
        assertEquals(2, SurfaceRules.driftCap(0));
    }

    @Test
    void meltingIsSlowJustAboveFreezingAndQuickWhenWarm() {
        assertEquals(0, SurfaceRules.meltRate(0, true, true), 1e-9);
        assertTrue(SurfaceRules.meltRate(0.5, false, false) < 0.3);
        assertEquals(1, SurfaceRules.meltRate(5, false, false), 1e-9);
        assertEquals(4, SurfaceRules.meltRate(30, false, false), 1e-9);
        assertTrue(SurfaceRules.meltRate(2, true, false) > SurfaceRules.meltRate(2, false, false));
        assertTrue(SurfaceRules.meltRate(2, false, true) > SurfaceRules.meltRate(2, false, false));
        // Rates and visits: 2.3 an hour is 2, sometimes 3.
        assertEquals(2, SurfaceRules.layersThisVisit(2.3, 0.5));
        assertEquals(3, SurfaceRules.layersThisVisit(2.3, 0.1));
    }

    @Test
    void lakesFreezeFromTheShoreAndRightAcrossInAHardFreeze() {
        assertEquals(0, SurfaceRules.freshFreezeChance(-0.5, true), 1e-9);
        assertTrue(SurfaceRules.freshFreezeChance(-2, true) > 0);
        assertEquals(0, SurfaceRules.freshFreezeChance(-5, false), 1e-9);
        assertEquals(1, SurfaceRules.freshFreezeChance(-5, true), 1e-9);
        assertEquals(1, SurfaceRules.freshFreezeChance(-10, false), 1e-9);
    }

    @Test
    void theSeaFreezesOnlyNearLandInDeepCold() {
        assertEquals(0, SurfaceRules.seaFreezeChance(-5, true, true), 1e-9);
        assertTrue(SurfaceRules.seaFreezeChance(-8, true, true) > 0);
        assertEquals(0, SurfaceRules.seaFreezeChance(-20, true, false), 1e-9);
        assertEquals(0, SurfaceRules.seaFreezeChance(-20, false, true), 1e-9);
    }

    @Test
    void iceThawsAboveFreezingFasterInRain() {
        assertEquals(0, SurfaceRules.thawChance(0, true), 1e-9);
        assertTrue(SurfaceRules.thawChance(1, true) > SurfaceRules.thawChance(1, false));
        assertEquals(1, SurfaceRules.thawChance(6, false), 1e-9);
    }

    @Test
    void vanillasSnowLineIsFreezing() {
        assertEquals(0.15f, SurfaceRules.vanillaTemperature(0), 1e-6);
        assertEquals(0.8f, SurfaceRules.vanillaTemperature(13), 1e-6);
        assertTrue(SurfaceRules.vanillaTemperature(-1) < 0.15f);
    }

    @Test
    void glazeBuildsAtSnowsPaceOnlyBelowFreezing() {
        assertEquals(SurfaceRules.snowChance(0.8, -5), SurfaceRules.glazeChance(0.8, -5), 1e-9);
        assertEquals(SurfaceRules.snowChance(0.4, -5), SurfaceRules.glazeChance(0.4, -5), 1e-9);
        assertEquals(0, SurfaceRules.glazeChance(1, 0), 1e-9);
        assertEquals(0, SurfaceRules.glazeChance(1, 2), 1e-9);
        assertEquals(0, SurfaceRules.glazeChance(0, -5), 1e-9);
        // A long ice storm (several hours of moderate freezing rain) reaches the cap: 2 layers.
        Random r = new Random(7);
        int layers = 0;
        for (int hour = 0; hour < 8; hour++) {
            if (r.nextDouble() < SurfaceRules.glazeChance(0.6, -3)) {
                layers = Math.min(SurfaceRules.GLAZE_MAX, layers + 1);
            }
        }
        assertEquals(2, layers);
    }

    @Test
    void glazeTextureIsWhiterAndClearerThanIce() {
        int ice = 0xBF91B7FD; // a typical vanilla ice pixel: pale blue, alpha 191
        int glaze = GlazeTextures.pixel(ice);
        int a = glaze >>> 24;
        int r = (glaze >> 16) & 0xFF;
        int g = (glaze >> 8) & 0xFF;
        int b = glaze & 0xFF;
        assertTrue(a < 0xBF, "more see-through");
        assertTrue(b - r < 0xFD - 0x91, "less blue");
        assertTrue(r > 0x91 && g > 0xB7, "whiter");
        assertTrue(b >= r, "still a touch of ice blue");
    }
}
