package dev.brights0ng.enginesandempires.weather.lightning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class FlashAndThunderTest {

    @Test
    void aStrikeFlickersInStrokesAndFades() {
        for (long seed = 0; seed < 200; seed++) {
            FlashLight f = FlashLight.of(seed, true, 1);
            assertTrue(f.strokes().size() >= 1 && f.strokes().size() <= 4);
            assertEquals(1.0, f.at(0), 1e-9, "full brightness at the first stroke");
            assertTrue(f.at(f.duration()) < 0.01, "faded by the end");
            assertTrue(f.duration() < 1000, "a strike is over within a second: " + f.duration());
        }
    }

    @Test
    void cloudFlashesAreSofterAndWeakStormsDimmer() {
        FlashLight cloud = FlashLight.of(3, false, 1);
        assertTrue(cloud.strokes().size() >= 2);
        assertTrue(cloud.at(0) < 0.8, "in-cloud flashes peak lower");
        assertTrue(FlashLight.of(3, true, 0).at(0) < FlashLight.of(3, true, 1).at(0), "weak storms flash dimmer");
    }

    @Test
    void theSameFlashFlickersTheSameEverywhere() {
        long seed = FlashLight.seed(1234.5, 300, -98765.25);
        assertEquals(FlashLight.of(seed, true, 0.7).strokes(), FlashLight.of(seed, true, 0.7).strokes());
    }

    @Test
    void strokeStartsAreFoundInAWindow() {
        FlashLight f = FlashLight.of(9, true, 1);
        assertTrue(f.strokeStarts(0, 1));
        assertFalse(f.strokeStarts(f.duration() + 1, f.duration() + 100));
    }

    @Test
    void thunderTakesThreeSecondsAKilometre() {
        List<ThunderPlan.Layer> l = ThunderPlan.of(1000, Double.NaN, false, 3072, 1);
        assertEquals(1000 / 343.0, l.get(0).delay(), 1e-9);
    }

    @Test
    void aCloseStrikeCracksAndAFarFlashRumbles() {
        List<ThunderPlan.Layer> close = ThunderPlan.of(60, 50, true, 3072, 1);
        assertTrue(close.stream().anyMatch(x -> x.sound() == ThunderPlan.Sound.IMPACT), "a close strike cracks");
        assertTrue(ThunderPlan.of(60, Double.NaN, false, 3072, 1).stream()
                .noneMatch(x -> x.sound() == ThunderPlan.Sound.IMPACT), "an in-cloud flash doesn't");
        List<ThunderPlan.Layer> far = ThunderPlan.of(1200, Double.NaN, true, 3072, 1);
        assertEquals(2, far.size(), "far thunder rolls in two layers");
        assertTrue(far.get(1).delay() > far.get(0).delay());
        assertTrue(far.get(0).pitch() < close.get(close.size() - 1).pitch(), "and is lower");
        assertTrue(far.get(0).volume() < close.get(close.size() - 1).volume(), "and quieter");
    }

    @Test
    void thunderFadesOutAtTheRange() {
        float edge = ThunderPlan.of(3000, Double.NaN, true, 3072, 1).get(0).volume();
        float mid = ThunderPlan.of(2000, Double.NaN, true, 3072, 1).get(0).volume();
        assertTrue(edge < mid && edge > 0, "fainter toward the range");
        assertTrue(ThunderPlan.of(4000, Double.NaN, true, 3072, 1).isEmpty(), "nothing beyond it");
    }
}
