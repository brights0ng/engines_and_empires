package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeophoneSlideTest {

    @Test
    void itStartsHeldOutByTheWholeSpikeAndEndsFullyIn() {
        assertEquals(GeophoneOrientation.SPIKE_LENGTH, GeophoneSlide.offset(0), 1.0e-12, "the tip just touches the face");
        assertEquals(0.0, GeophoneSlide.offset(GeophoneSlide.TICKS), 1.0e-12, "the crossguard meets the face");
    }

    @Test
    void itNeverGoesFurtherInThanTheCrossguardAllowsOrFurtherOutThanTheSpike() {
        for (double age = -10.0; age <= 20.0; age += 0.25) {
            double offset = GeophoneSlide.offset(age);
            assertTrue(offset >= 0.0, "past the crossguard at age " + age);
            assertTrue(offset <= GeophoneOrientation.SPIKE_LENGTH + 1.0e-12, "further out than the spike at age " + age);
        }
    }

    @Test
    void itOnlyEverMovesInward() {
        double previous = Double.POSITIVE_INFINITY;
        for (double age = 0.0; age <= GeophoneSlide.TICKS; age += 0.1) {
            double offset = GeophoneSlide.offset(age);
            assertTrue(offset <= previous + 1.0e-12, "moved outward at age " + age);
            previous = offset;
        }
    }

    /** Driven home: the speed rises through the slide, so it is fastest at the moment it stops. */
    @Test
    void itSpeedsUpAsItGoes() {
        double early = GeophoneSlide.offset(0.0) - GeophoneSlide.offset(1.0);
        double late = GeophoneSlide.offset(GeophoneSlide.TICKS - 1.0) - GeophoneSlide.offset(GeophoneSlide.TICKS);
        assertTrue(late > 2.0 * early, "the last tick moves " + late + ", the first " + early);
    }

    @Test
    void aGeophoneThatLoadsLongAfterItWasPlacedIsSimplyFullyIn() {
        assertEquals(0.0, GeophoneSlide.offset(1_000_000), 0.0);
        assertEquals(0.0, GeophoneSlide.offset(GeophoneSlide.TICKS + 0.001), 0.0);
        assertTrue(GeophoneSlide.isDone(1_000_000));
    }

    @Test
    void aClockThatIsAFewTicksBehindHoldsItAtTheStart() {
        assertEquals(GeophoneOrientation.SPIKE_LENGTH, GeophoneSlide.offset(-3.0), 1.0e-12);
        assertEquals(0.0, GeophoneSlide.progress(-3.0), 0.0);
        assertFalse(GeophoneSlide.isDone(-3.0));
    }

    @Test
    void itIsShort() {
        assertTrue(GeophoneSlide.TICKS >= 3 && GeophoneSlide.TICKS <= 10, "a short animation, not a slow one");
    }
}
