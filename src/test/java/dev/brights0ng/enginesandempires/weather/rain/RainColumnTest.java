package dev.brights0ng.enginesandempires.weather.rain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The renderer's per-column memory and the eased drift: rain already falling keeps falling, nothing jumps. */
class RainColumnTest {

    private static final int GROUND = 64;
    private static final double TOP = 112;

    @Test
    void aColumnFirstSeenRainingIsRainingAllTheWayDown() {
        RainColumn c = RainColumn.first(0.8, false, GROUND, 0);
        assertTrue(c.active && c.landing(), "walking into a shower, it is already raining at your feet");
        RainColumn dry = RainColumn.first(0, false, GROUND, 0);
        assertFalse(dry.active);
    }

    @Test
    void newRainArrivesFromAboveAtTheFallSpeed() {
        RainColumn c = RainColumn.first(0, false, GROUND, 0);
        c.target = 0.9;
        c.step(TOP);
        assertTrue(c.active);
        double perTick = RainColumn.fallPerTick(false, 0.9);
        assertEquals(TOP - perTick, c.head, 1e-9, "the front starts at the top");
        int ticks = 1;
        while (c.head > GROUND) {
            c.step(TOP);
            ticks++;
        }
        double seconds = ticks / 20.0;
        System.out.printf("heavy rain takes %.1f s to fall %.0f blocks%n", seconds, TOP - GROUND);
        assertEquals((TOP - GROUND) / 8.75, seconds, 0.2, "at about 9 m/s");
        assertTrue(c.landing());
    }

    @Test
    void stoppingRainDrainsOutOfTheBottom() {
        RainColumn c = RainColumn.first(0.5, false, GROUND, 0);
        c.target = 0;
        c.step(TOP);
        assertTrue(c.active, "the last drops are still falling");
        assertTrue(c.tail < TOP && c.head == GROUND, "the top of the rain falls; it still reaches the ground");
        int ticks = 1;
        while (c.active) {
            c.step(TOP);
            ticks++;
        }
        assertTrue(ticks > 20, "it takes the fall time to drain: " + ticks + " ticks");
    }

    @Test
    void snowArrivesSlowly() {
        RainColumn c = RainColumn.first(0, true, GROUND, 0);
        c.target = 0.5;
        int ticks = 0;
        do {
            c.step(TOP);
            ticks++;
        } while (c.head > GROUND);
        assertEquals((TOP - GROUND) / RainModel.SNOW_FALL, ticks / 20.0, 0.2, "at 1.2 m/s");
        assertFalse(c.landing(), "and snow never splashes");
    }

    @Test
    void strengthEasesInsteadOfJumping() {
        RainColumn c = RainColumn.first(0.2, false, GROUND, 0);
        c.target = 0.9;
        c.step(TOP);
        assertTrue(c.strength > 0.2 && c.strength < 0.3);
        for (int i = 0; i < 100; i++) {
            c.step(TOP);
        }
        assertEquals(0.9, c.strength, 0.01);
    }

    @Test
    void interpolatedEndsDontBlowUpWhileRainKeepsComing() {
        RainColumn c = RainColumn.first(0.5, false, GROUND, 0);
        c.step(TOP);
        assertTrue(Double.isInfinite(c.tailAt(0.5f)));
        c.target = 0;
        c.step(TOP);
        assertTrue(Double.isFinite(c.tailAt(0.5f)), "the first tick of stopping interpolates from its new top");
    }

    @Test
    void driftVelocityEasesOverSeconds() {
        VelocitySmoother s = new VelocitySmoother();
        UUID id = UUID.randomUUID();
        s.smooth(id, 0.30, 0, 0);
        // A once-a-second step of 20% in the measured velocity.
        double[] after1s = s.smooth(id, 0.36, 0, 20);
        assertTrue(after1s[0] < 0.31, "a second later it has barely moved: " + after1s[0]);
        double[] v = after1s;
        for (long t = 40; t <= 2000; t += 20) {
            v = s.smooth(id, 0.36, 0, t);
        }
        assertEquals(0.36, v[0], 0.001, "it gets there in the end");
        double[] same = s.smooth(id, 9, 9, 2000);
        assertEquals(v[0], same[0], 1e-12, "the same tick twice changes nothing");
    }
}
