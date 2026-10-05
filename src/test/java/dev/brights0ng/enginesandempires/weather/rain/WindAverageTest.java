package dev.brights0ng.enginesandempires.weather.rain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The drift wind, averaged over the rain's fall around the players, with no seams. */
class WindAverageTest {

    @Test
    void aGustBarelyMovesTheDriftButSteadyWindGetsThere() {
        WindAverage avg = new WindAverage();
        double[] wind = {4, 0};
        WindAverage.Raw raw = (x, z) -> wind.clone();
        avg.observe(0, 0, 0, raw);
        assertEquals(4, avg.at(10, 10, raw)[0], 1e-9, "it starts at the wind now");
        wind[0] = 12;
        avg.observe(0, 0, 20, raw);
        avg.observe(0, 0, 40, raw);
        wind[0] = 4;
        avg.observe(0, 0, 60, raw);
        assertTrue(avg.at(10, 10, (x, z) -> null)[0] < 4.8, "a two-second gust moves it under 0.8 m/s");
        wind[0] = 8;
        for (long t = 80; t <= 3000; t += 20) {
            avg.observe(0, 0, t, raw);
        }
        assertEquals(8, avg.at(10, 10, raw)[0], 0.01);
    }

    @Test
    void blendsBetweenNodesWithoutSeams() {
        WindAverage avg = new WindAverage();
        // East-west wind growing toward +x.
        WindAverage.Raw raw = (x, z) -> new double[]{x / 512.0, 0};
        avg.observe(0, 0, 0, raw);
        assertEquals(0.5, avg.at(256, 0, raw)[0], 1e-9, "half way between two nodes");
        double left = avg.at(511.99, 100, raw)[0];
        double right = avg.at(512.01, 100, raw)[0];
        assertEquals(left, right, 1e-3, "no step at a node");
    }

    @Test
    void farFromPlayersItIsTheWindNow() {
        WindAverage avg = new WindAverage();
        avg.observe(0, 0, 0, (x, z) -> new double[]{1, 1});
        assertEquals(9, avg.at(100_000, 0, (x, z) -> new double[]{9, 0})[0], 1e-9);
        avg.forget(10_000);
        assertEquals(0, avg.size(), "nodes no player is near are forgotten");
    }
}
