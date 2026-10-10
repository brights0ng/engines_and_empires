package dev.brights0ng.enginesandempires.weather.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.field.Moisture;

/** The climate from the world's noise (2026-10-10). */
class NoiseClimateTest {

    private static final BiomeAdjust FOREST = new BiomeAdjust(0, 0, Double.NaN, BiomeClimate.Surface.FOREST, false);
    private static final BiomeAdjust JUNGLE = new BiomeAdjust(2, 0.05, Double.NaN, BiomeClimate.Surface.FOREST, false);
    private static final BiomeAdjust DESERT = new BiomeAdjust(0, 0, 0.08, BiomeClimate.Surface.LAND, false);
    private static final BiomeAdjust WATER = new BiomeAdjust(0, 0, Double.NaN, BiomeClimate.Surface.WATER, false);

    @Test
    void bandCentresGetTheirAnalogueClimates() {
        assertEquals(-10, NoiseClimate.mean(-0.72), 1e-9, "frozen band: snowy plains");
        assertEquals(3, NoiseClimate.mean(-0.30), 1e-9, "cold band: taiga");
        assertEquals(11, NoiseClimate.mean(0.025), 1e-9, "temperate band: plains and forests");
        assertEquals(25, NoiseClimate.mean(0.375), 1e-9, "warm band: savanna and jungle");
        assertEquals(25, NoiseClimate.mean(0.775), 1e-9, "hot band: desert");
        assertEquals(3, NoiseClimate.swing(0.375), 1e-9, "the tropics barely have seasons");
        assertEquals(15, NoiseClimate.swing(-0.72), 1e-9);
    }

    @Test
    void theClimateChangesSmoothly() {
        for (double t = -1; t < 1; t += 0.01) {
            assertTrue(Math.abs(NoiseClimate.mean(t + 0.01) - NoiseClimate.mean(t)) < 0.6, "no jumps at " + t);
            assertTrue(NoiseClimate.mean(t + 0.01) >= NoiseClimate.mean(t) - 1e-9, "warmer up the noise at " + t);
        }
    }

    @Test
    void plainsBesideAJungleReadWarmToo() {
        // The case that started this: vanilla puts plains next to jungles. Both sit in warm, humid noise near the
        // border, so the plains read warm and the step between them is small.
        BiomeClimate jungle = NoiseClimate.resolve(0.30, 0.35, JUNGLE);
        BiomeClimate plains = NoiseClimate.resolve(0.27, 0.30, BiomeAdjust.NONE);
        assertTrue(plains.mean() > 18, "the plains are warm: " + plains.mean());
        assertTrue(jungle.mean() - plains.mean() < 4, "a small step into the jungle: " + plains.mean() + " -> "
                + jungle.mean());
    }

    @Test
    void aJunglesAirIsTropical() {
        BiomeClimate jungle = NoiseClimate.resolve(0.40, 0.60, JUNGLE);
        assertTrue(jungle.mean() >= 26, "hot: " + jungle.mean());
        assertTrue(jungle.humidity() > 0.9, "humid: " + jungle.humidity());
        double target = Moisture.targetHumidity(BiomeClimate.Surface.FOREST.ordinal(), jungle.humidity());
        assertTrue(target > 0.85, "the forest keeps its air near saturation: " + target);
    }

    @Test
    void desertsStayDryWhateverTheHumidityNoiseSays() {
        BiomeClimate hotCountry = NoiseClimate.resolve(0.80, 0.80, BiomeAdjust.NONE);
        assertTrue(hotCountry.humidity() < 0.16, "the hot band dries even humid noise: " + hotCountry.humidity());
        BiomeClimate desert = NoiseClimate.resolve(0.80, 0.80, DESERT);
        assertEquals(0.08, desert.humidity(), 1e-9, "and a desert is as dry as its entry says");
        BiomeClimate savanna = NoiseClimate.resolve(0.40, -0.60, BiomeAdjust.NONE);
        assertTrue(savanna.humidity() < 0.2, "arid warm noise is dry too (savanna): " + savanna.humidity());
    }

    @Test
    void waterTempersTheSeasonsAndKeepsTheAirMoist() {
        BiomeClimate land = NoiseClimate.resolve(0.0, -0.5, FOREST);
        BiomeClimate sea = NoiseClimate.resolve(0.0, -0.5, WATER);
        assertEquals(land.swing() / 2, sea.swing(), 1e-9);
        assertTrue(sea.humidity() >= 0.8);
        assertEquals(BiomeClimate.Surface.WATER, sea.surface());
    }

    @Test
    void theFrozenFlagCarriesThrough() {
        BiomeClimate c = NoiseClimate.resolve(1, 1, BiomeAdjust.NONE.asFrozen());
        assertTrue(c.frozen());
        Baseline.Sample s = Baseline.combine(new ClimateField.Regional(c.mean(), c.swing(), c.humidity()), c, 0.5,
                1, 10, 5, 1, 0);
        assertTrue(s.temperature() <= BiomeClimate.FROZEN_MAX, "held below freezing however hot: " + s.temperature());
    }
}
