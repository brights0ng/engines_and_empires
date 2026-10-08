package dev.brights0ng.enginesandempires.weather.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;

/** Clouds ease into new velocities (Bright, 2026-10-07: movement was erratic when velocities changed). */
class CloudDriftTest {

    @Test
    void positionIsTheIntegralOfAnEasedVelocity() {
        double v0 = 0.1, v1 = 0.3;
        // Numerically integrate the velocity and compare.
        double pos = 0;
        for (int t = 0; t < 900; t++) {
            pos += CloudDrift.velocity(v0, v1, t + 0.5);
            assertEquals(pos, CloudDrift.offset(v0, v1, t + 1), 1e-3, "at " + (t + 1));
        }
        assertEquals(v0, CloudDrift.velocity(v0, v1, 0), 1e-12, "starts at the old velocity");
        assertEquals(v1, CloudDrift.velocity(v0, v1, CloudDrift.EASE_TICKS), 1e-12, "settles on the new one");
        // Halfway through, about halfway between.
        assertEquals(0.2, CloudDrift.velocity(v0, v1, CloudDrift.EASE_TICKS / 2), 1e-9);
    }

    @Test
    void reSteeringMidEaseNeverJumpsOrJerks() {
        SimCloud c = new SimCloud(UUID.randomUUID(), CloudType.CUMULUS_HUMILIS, 0, 0, 0.1, 0, 0, 0, 100_000, 100, 20,
                0.8f, 0, 0, List.of(new SimCloud.Dome(0, 0, 80, 1, 1)), 0, false);
        c.tvx = 0.3;
        long now = 200;
        double before = c.xAt(now), vBefore = c.vxAt(now);
        c.advance(now);
        c.tvx = -0.2;
        assertEquals(before, c.xAt(now), 1e-9, "same place");
        assertEquals(vBefore, c.vxAt(now), 1e-12, "same speed");
        // And the speed changes gently right after: no more than the S-curve allows in a tick.
        double dv = Math.abs(c.vxAt(now + 1) - c.vxAt(now));
        assertTrue(dv < 1e-4, "gentle start: " + dv);
    }
}
