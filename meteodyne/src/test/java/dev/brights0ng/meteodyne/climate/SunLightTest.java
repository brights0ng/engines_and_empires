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
}
