package dev.brights0ng.enginesandempires.weather.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.brights0ng.enginesandempires.weather.climate.Underground.CaveRule;
import dev.brights0ng.enginesandempires.weather.climate.Underground.Settings;
import org.junit.jupiter.api.Test;

/** Weather phase 8c: underground temperature (reworked 2026-10-15: band from y40 to y0, one gradient below). */
class UndergroundTest {

    private static final Settings S = Settings.DEFAULT;
    private static final double BOTTOM = -64;

    private static double t(double outside, double annual, double depth, int sky, double y, double noise,
                            CaveRule cave) {
        return Underground.read(outside, annual, depth, sky, y, BOTTOM, noise, cave, S).temperature();
    }

    private static CaveRule cave(String id) {
        return S.caves().get(id);
    }

    @Test
    void theOpenAirIsUnchanged() {
        assertEquals(-12, t(-12, 10, 0, 15, 70, 1, null), 1e-9);
        // Cover with the sky in full view (a ravine, a pit) is outside air too, even deep down.
        assertEquals(-12, t(-12, 10, 60, 15, -40, 1, null), 1e-9);
    }

    @Test
    void aboveTheBandTheSurfacesYearEasesInOverTwentyBlocks() {
        assertEquals(10, t(-15, 10, 20, 0, 60, 1, null), 1e-9);
        assertEquals(-2.5, t(-15, 10, 10, 0, 60, 1, null), 1e-9);
        // No warming with depth above the band: deep under a mountain is the surface's year.
        assertEquals(6, t(15, 6, 200, 0, 100, 1, null), 1e-9);
    }

    @Test
    void theBandPullsTowardTwentyOne() {
        assertEquals(0, Underground.pull(40, S), 1e-9);
        assertEquals(0.5, Underground.pull(20, S), 1e-9);
        assertEquals(1, Underground.pull(0, S), 1e-9);
        assertEquals(10.5, t(-15, 0, 60, 0, 20, 0, null), 1e-9);
        // The texture grows with the pull: half strength half way.
        assertEquals(12, t(-15, 0, 60, 0, 20, 1, null), 1e-9);
    }

    @Test
    void belowZeroOneGradientToBedrockWhateverTheSurface() {
        assertEquals(21, Underground.rock(0, BOTTOM, S), 1e-9);
        assertEquals(33, Underground.rock(-32, BOTTOM, S), 1e-9);
        assertEquals(45, Underground.rock(-64, BOTTOM, S), 1e-9);
        assertEquals(33, t(-30, -20, 80, 0, -32, 0, null), 1e-9);
        assertEquals(33, t(35, 25, 80, 0, -32, 0, null), 1e-9);
        // Texture in full: +/-3 C.
        assertEquals(30, t(10, 10, 80, 0, -32, -1, null), 1e-9);
    }

    @Test
    void skylightLetsTheOutsideAirIntoCaveMouths() {
        double deep = t(-15, 10, 60, 0, -10, 0, null);
        double mouth = t(-15, 10, 60, 10, -10, 0, null);
        assertTrue(mouth < deep && mouth > -15, "a cave mouth sits between: " + mouth);
    }

    @Test
    void lushCavesNeverFreeze() {
        CaveRule lush = cave("minecraft:lush_caves");
        assertEquals(1, t(-30, -12, 30, 0, 50, 0, lush), 1e-9);
        assertTrue(t(-30, -12, 30, 12, 50, 0, lush) >= 1, "even at a lush cave's mouth");
        // Elsewhere just a little warmer.
        assertEquals(t(5, 12, 40, 0, 20, 0, null) + 3, t(5, 12, 40, 0, 20, 0, lush), 1e-9);
    }

    @Test
    void theDeepDarkIsCold() {
        CaveRule dark = cave("minecraft:deep_dark");
        double plain = t(15, 12, 100, 0, -50, 0, null);
        assertTrue(plain > 33, "plain rock at y-50: " + plain);
        assertEquals(5, t(15, 12, 100, 0, -50, 1, dark), 1e-9);
        assertTrue(t(15, 12, 100, 0, -50, -1, dark) <= 5, "never past its cap");
    }

    @Test
    void dripstoneIsNeutral() {
        assertEquals(t(5, 12, 50, 0, -10, 0.3, null),
                t(5, 12, 50, 0, -10, 0.3, cave("minecraft:dripstone_caves")), 1e-9);
    }

    @Test
    void caveEntriesParse() {
        CaveRule r = CaveRule.parse("mymod:glow_caves;offset=-2.5;useMax=true;max=12");
        assertEquals("mymod:glow_caves", r.biome());
        assertEquals(-2.5, r.offset(), 1e-9);
        assertFalse(r.useMin());
        assertTrue(r.useMax());
        assertEquals(12, r.max(), 1e-9);
        assertEquals(0, CaveRule.parse("mymod:plain").offset(), 1e-9);
        assertFalse(CaveRule.valid("no_namespace;offset=1"));
        assertFalse(CaveRule.valid("minecraft:x;offset=hot"));
        assertFalse(CaveRule.valid("minecraft:x;colour=blue"));
        assertEquals(3, S.caves().size());
        // A bad entry is skipped, not fatal.
        assertNull(Settings.caves(List.of("bad", "minecraft:lush_caves")).get("bad"));
    }

    private static double rh(double outside, double depth, int sky, CaveRule cave) {
        return Underground.humidity(outside, Underground.insulation(depth, sky, S), cave, S);
    }

    @Test
    void theOpenAirKeepsItsOwnHumidity() {
        assertEquals(0.3, rh(0.3, 0, 15, null), 1e-9);
        // Cover with the sky in view is outside air too.
        assertEquals(0.9, rh(0.9, 60, 15, null), 1e-9);
    }

    @Test
    void underCoverHumidityIsOneFlatValueWhateverIsOverhead() {
        double flat = Settings.DEFAULT_HUMIDITY;
        assertEquals(flat, rh(0.1, 40, 0, null), 1e-9);
        assertEquals(flat, rh(0.95, 40, 0, null), 1e-9);
        assertEquals(flat, S.humidity(), 1e-9);
    }

    @Test
    void humidityEasesInWithTheTemperaturesInsulation() {
        // Ten blocks of cover is half way (smooth step at 0.5), as for the temperature.
        assertEquals(0.2 + 0.5 * (Settings.DEFAULT_HUMIDITY - 0.2), rh(0.2, 10, 0, null), 1e-9);
        // A cave mouth with some sky light is only part way.
        double mouth = rh(0.2, 40, 12, null);
        assertTrue(mouth > 0.2 && mouth < Settings.DEFAULT_HUMIDITY, "mouth: " + mouth);
    }

    @Test
    void caveBiomesMayHaveTheirOwnHumidity() {
        assertEquals(0.85, rh(0.3, 40, 0, cave("minecraft:lush_caves")), 1e-9);
        assertEquals(0.35, rh(0.3, 40, 0, cave("minecraft:deep_dark")), 1e-9);
        // Dripstone sets none: the flat value.
        assertEquals(Settings.DEFAULT_HUMIDITY, rh(0.3, 40, 0, cave("minecraft:dripstone_caves")), 1e-9);
        // And a biome's own humidity gives way to the outside air at its mouth too.
        assertEquals(0.3, rh(0.3, 40, 15, cave("minecraft:lush_caves")), 1e-9);
    }

    @Test
    void humidityEntriesParse() {
        assertEquals(0.4, CaveRule.parse("mymod:x;humidity=0.4").humidity(), 1e-9);
        assertTrue(CaveRule.parse("mymod:x;humidity=0.4").hasHumidity());
        assertFalse(CaveRule.parse("mymod:x;offset=2").hasHumidity());
        assertTrue(CaveRule.valid("mymod:x;humidity=0"));
        assertTrue(CaveRule.valid("mymod:x;humidity=1"));
        assertFalse(CaveRule.valid("mymod:x;humidity=1.5"));
        assertFalse(CaveRule.valid("mymod:x;humidity=-0.1"));
        assertFalse(CaveRule.valid("mymod:x;humidity=wet"));
    }
}
