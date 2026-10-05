package dev.brights0ng.enginesandempires.weather.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The phase 0 baseline: vanilla biome temperatures as degrees C. */
class TemperatureTest {

    @Test
    void vanillaSnowLineIsFreezing() {
        assertEquals(0, Temperature.fromVanilla(0.15f), 1e-5);
    }

    @Test
    void familiarBiomesComeOutPlausible() {
        assertEquals(13, Temperature.fromVanilla(0.8f), 1e-5, "plains");
        assertEquals(37, Temperature.fromVanilla(2.0f), 1e-5, "desert");
        assertEquals(-3, Temperature.fromVanilla(0.0f), 1e-5, "snowy plains");
    }
}
