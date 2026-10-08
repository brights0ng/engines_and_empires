package dev.brights0ng.enginesandempires.weather.rain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WetnessTest {

    @Test
    void desertsGetOnlyLightRainAndTemperateCountryAllOfIt() {
        assertEquals(RainModel.DRY_SHARE, RainModel.wetness(0.08), 1e-9);
        assertEquals(1, RainModel.wetness(0.5), 1e-9);
        double savanna = RainModel.wetness(0.3);
        assertTrue(savanna > 0.4 && savanna < 0.9, "savanna in between: " + savanna);
        // Smooth: no step anywhere.
        for (double h = 0; h < 1; h += 0.01) {
            assertTrue(Math.abs(RainModel.wetness(h + 0.01) - RainModel.wetness(h)) < 0.05, "smooth at " + h);
        }
    }
}
