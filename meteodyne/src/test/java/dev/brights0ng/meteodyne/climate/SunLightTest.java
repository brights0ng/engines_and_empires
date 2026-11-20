// Edited by Claude: the season's strength tests.
package dev.brights0ng.meteodyne.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SunLightTest {

    @Test
    void theSunIsOverheadAtNoonAndRisesInTheEast() {
        double[] noon = SunLight.direction(0);
        assertEquals(1, noon[1], 1e-12);
        assertEquals(0, noon[0], 1e-12);
        double[] sunrise = SunLight.direction(0.75);
        assertEquals(1, sunrise[0], 1e-9, "rises in the east (+x)");
        assertEquals(0, sunrise[1], 1e-9);
        double[] sunset = SunLight.direction(0.25);
        assertEquals(-1, sunset[0], 1e-9, "sets in the west (-x)");
        double[] morning = SunLight.direction(0.9);
        assertTrue(morning[0] > 0 && morning[1] > 0, "in the morning it is up in the east");
        double[] midnight = SunLight.direction(0.5);
        assertEquals(-1, midnight[1], 1e-12);
        assertEquals(1, Math.sqrt(morning[0] * morning[0] + morning[1] * morning[1] + morning[2] * morning[2]), 1e-12);
    }

    @Test
    void heightIsZeroByNightAndRisesToNoon() {
        assertEquals(0, SunLight.height(SunLight.direction(0.5)[1]), 1e-12);
        assertEquals(0, SunLight.height(SunLight.direction(0.4)[1]), 1e-12);
        assertEquals(1, SunLight.height(SunLight.direction(0)[1]), 1e-12);
        assertTrue(SunLight.height(SunLight.direction(0.95)[1]) < SunLight.height(SunLight.direction(0.98)[1]));
    }

    @Test
    void blocksLetOneQuarterThroughNoMatterHowMany() {
        assertEquals(1, SunLight.blockShare(SunLight.PASS), 1e-12);
        assertEquals(0.25, SunLight.blockShare(SunLight.PARTLY), 1e-12);
        assertEquals(0, SunLight.blockShare(SunLight.STOPS), 1e-12);
    }

    @Test
    void cloudsFadeSmoothlyByThickness() {
        assertEquals(1, SunLight.cloudShare(0), 1e-12);
        assertTrue(SunLight.cloudShare(0.3) > SunLight.cloudShare(1));
        assertTrue(SunLight.cloudShare(1) > SunLight.cloudShare(3));
        assertTrue(SunLight.cloudShare(8) < 0.001);
    }

    @Test
    void theStrongerObscurerWinsAndTheyDoNotMultiply() {
        // Bright's example, the right way round: glass (25%) under cloud letting 50% through is 25%
        assertEquals(0.25, SunLight.through(0.25, 0.5), 1e-12);
        // glass under thick cloud letting 10% through is 10%
        assertEquals(0.10, SunLight.through(0.25, 0.10), 1e-12);
        // clear air under cloud at 50%
        assertEquals(0.5, SunLight.through(1, 0.5), 1e-12);
        // a solid block beats anything
        assertEquals(0, SunLight.through(0, 1), 1e-12);
    }

    @Test
    void warmthIsMaxTimesHeightTimesThrough() {
        assertEquals(8, SunLight.warmth(8, 1, 1), 1e-12);
        assertEquals(4, SunLight.warmth(8, 0.5, 1), 1e-12);
        assertEquals(2, SunLight.warmth(8, 1, 0.25), 1e-12);
        assertEquals(0, SunLight.warmth(8, 0, 1), 1e-12);
        assertEquals(0, SunLight.warmth(0, 1, 1), 1e-12, "max 0 turns it off");
    }

    private static final double TILT = 23.44, LATITUDE = 37.55;

    @Test
    void earthsTiltHalvesTheSunFromMidsummerToMidwinter() {
        assertEquals(1, SunLight.season(TILT, LATITUDE, ClimateCurves.sun(ClimateCurves.SUN_PEAK)), 1e-12);
        assertEquals(0.5, SunLight.season(TILT, LATITUDE, ClimateCurves.sun(ClimateCurves.SUN_PEAK + 0.5)), 1e-3);
        double equinox = SunLight.season(TILT, LATITUDE, ClimateCurves.sun(0));
        assertEquals(Math.cos(Math.toRadians(LATITUDE)) / Math.cos(Math.toRadians(LATITUDE - TILT)), equinox, 1e-12);
        assertEquals(0.818, equinox, 1e-3);
        assertEquals(equinox, SunLight.season(TILT, LATITUDE, Double.NaN), 1e-12, "no seasons: the equinox's");
        // it climbs steadily from midwinter to midsummer
        double last = 0;
        for (int i = 0; i <= 6; i++) {
            double s = SunLight.season(TILT, LATITUDE, ClimateCurves.sun(ClimateCurves.SUN_PEAK + 0.5 + i / 12.0));
            assertTrue(s > last, "rising at sub-season " + i + ": " + s);
            last = s;
        }
    }

    @Test
    void theTiltSetsHowHardWinterIs() {
        assertEquals(1, SunLight.season(0, LATITUDE, -1), 1e-12, "no tilt: the same sun all year");
        assertEquals(1, SunLight.season(0, LATITUDE, 1), 1e-12);
        double mild = SunLight.season(10, LATITUDE, -1), harsh = SunLight.season(35, LATITUDE, -1);
        assertTrue(mild > 0.5 && harsh < 0.5, mild + ", " + harsh);
        assertEquals(Math.cos(Math.toRadians(LATITUDE + 35)) / Math.cos(Math.toRadians(LATITUDE - 35)), harsh, 1e-12);
        // past the polar circle the winter sun doesn't rise at noon
        assertEquals(0, SunLight.season(TILT, 70, -1), 1e-12);
        assertEquals(1, SunLight.season(TILT, 70, 1), 1e-12);
    }

    @Test
    void inTheTropicsTheStrongestSunIsOverhead() {
        // at 10 degrees the sun is overhead in late spring; midsummer is a little past it
        double midsummer = SunLight.season(TILT, 10, 1);
        assertEquals(Math.cos(Math.toRadians(TILT - 10)), midsummer, 1e-12);
        assertTrue(midsummer < 1);
        assertEquals(1, SunLight.season(TILT, 10, Math.sin(Math.toRadians(10)) / Math.sin(Math.toRadians(TILT))), 1e-12);
        assertEquals(0, SunLight.season(0, 90, 0), 1e-12, "the pole with no tilt: no sun");
    }
}
