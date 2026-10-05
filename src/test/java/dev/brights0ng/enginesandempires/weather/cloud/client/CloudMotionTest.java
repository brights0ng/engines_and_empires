package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Smoothed cloud motion: measured velocity, no jumps at updates, corrections eased out. */
class CloudMotionTest {

    @Test
    void velocityIsMeasuredNotTakenFromOneGustyTick() {
        // PA's update says 0.5 b/t (a gust), but the cloud really moved 4 blocks in 20 ticks.
        CloudMotion m = new CloudMotion(0, 0, 100, 0.2, 0);
        m.observe(4, 0, 120, 0.5, 0, 120);
        assertEquals(0.2, m.vx(), 1e-9);
    }

    @Test
    void noJumpWhenAnUpdateDisagrees() {
        CloudMotion m = new CloudMotion(0, 0, 100, 0.5, 0); // a gusty first velocity: overshoots
        double before = m.x(120);                             // drawn at 10 by tick 120
        m.observe(4, 0, 120, 0.2, 0, 120);                    // it was really at 4
        assertEquals(before, m.x(120), 1e-9, "same place the instant the update lands");
        double eased = m.x(120 + CloudMotion.EASE_TICKS);
        assertEquals(4 + 0.2 * CloudMotion.EASE_TICKS, eased, 1e-9, "correction gone after the ease");
    }

    @Test
    void teleportsAreTakenAtOnce() {
        CloudMotion m = new CloudMotion(0, 0, 100, 0, 0);
        m.observe(500, 0, 120, 0, 0, 120);
        assertEquals(500, m.x(120), 1e-9);
    }

    @Test
    void repeatedUpdateIsIgnored() {
        CloudMotion m = new CloudMotion(0, 0, 100, 0.2, 0);
        m.observe(0, 0, 100, 9, 0, 110);
        assertEquals(0.2, m.vx(), 1e-9);
    }

    @Test
    void aBigJumpGlidesInsteadOfSnapping() {
        CloudMotion m = new CloudMotion(0, 0, 100, 0, 0);
        // The server moved the cluster 150 blocks at once.
        m.observe(150, 0, 120, 0, 0, 120);
        assertEquals(0, m.x(120), 1e-9, "no jump");
        assertEquals(75, m.x(120 + CloudMotion.BIG_EASE_TICKS / 2), 1e-9, "half way after half the ease");
        assertEquals(150, m.x(120 + CloudMotion.BIG_EASE_TICKS), 1e-9);
    }
}
