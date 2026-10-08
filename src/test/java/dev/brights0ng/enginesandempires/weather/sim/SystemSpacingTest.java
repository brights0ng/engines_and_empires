package dev.brights0ng.enginesandempires.weather.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/**
 * 2026-10-06: highs and lows don't sit on top of each other (a high born 600 blocks from a mature low cancelled it out on
 * the map while its fronts still made rain).
 */
class SystemSpacingTest {

    @Test
    void newHighsKeepClearOfLows() {
        List<WeatherSystem> systems = new ArrayList<>();
        systems.add(new WeatherSystem(1, WeatherSystem.Kind.LOW, 0, 0, 0, 1, 50_000, 100_000, 26, 4500, false));
        SystemsSim sim = new SystemsSim(new JetStream(SimParams.DEFAULT, 1), 1, systems, 2);
        assertTrue(sim.crowded(WeatherSystem.Kind.LOW, 600, 0, 0), "a high 600 blocks from a low is refused");
        assertFalse(sim.crowded(WeatherSystem.Kind.LOW, 8000, 0, 0), "8000 away is fine");
    }

    /** 2026-10-06 flight test: a new high formed 5000 blocks from another, overlapping it. */
    @Test
    void newSystemsKeepClearOfTheirOwnKind() {
        List<WeatherSystem> systems = new ArrayList<>();
        systems.add(new WeatherSystem(1, WeatherSystem.Kind.HIGH, 0, 0, 0, 1, 50_000, 100_000, 14, 6900, false));
        SystemsSim sim = new SystemsSim(new JetStream(SimParams.DEFAULT, 1), 1, systems, 2);
        assertTrue(sim.sameKindNear(WeatherSystem.Kind.HIGH, 5000, 0, 6300), "a high 5000 from a high is refused");
        assertFalse(sim.sameKindNear(WeatherSystem.Kind.HIGH, 9000, 0, 6300), "9000 away is fine");
        assertFalse(sim.sameKindNear(WeatherSystem.Kind.LOW, 5000, 0, 4000), "other kinds are crowded() 's job");
    }

    @Test
    void lowsAndHighsStayApartOverWeeks() {
        List<WeatherSystem> systems = new ArrayList<>();
        SystemsSim sim = new SystemsSim(new JetStream(SimParams.DEFAULT, 7), 7, systems, 1);
        List<SystemsSim.Anchor> player = List.of(new SystemsSim.Anchor(0, 0));
        SplittableRandom nudges = new SplittableRandom(3);
        double closest = Double.POSITIVE_INFINITY;
        int samples = 0;
        int overlaps = 0;
        for (long t = 1000; t <= 20 * 24000; t += 1000) {
            sim.step(t, 1000, Math.cos(t / 24000.0 / 40 * 2 * Math.PI), player, (x, z) -> true, nudges);
            for (WeatherSystem l : systems) {
                if (l.kind != WeatherSystem.Kind.LOW || l.strength() / l.peak < 0.5) {
                    continue;
                }
                for (WeatherSystem h : systems) {
                    if (h.kind != WeatherSystem.Kind.HIGH || h.strength() / h.peak < 0.5) {
                        continue;
                    }
                    double d = Math.hypot(l.x - h.x, l.z - h.z) / l.radius();
                    closest = Math.min(closest, d);
                    samples++;
                    if (d < 0.5) {
                        overlaps++;
                    }
                }
            }
        }
        System.out.printf("strong low-high pairs: closest %.2f low radii, %d of %d samples under half a radius%n",
                closest, overlaps, samples);
        assertTrue(overlaps <= samples / 200, "strong lows and highs (almost) never overlap: " + overlaps);
    }
}
