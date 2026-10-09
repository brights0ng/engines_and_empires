package dev.brights0ng.enginesandempires.weather.sky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;

class StormShadeTest {

    private static CloudShape cloud(double x, double z, float radius, float base, float top, float density,
                                    float coverage, float anvil, float storm) {
        UUID id = UUID.randomUUID();
        return new CloudShape(id, id, "minecraft:overworld", x, z, 0, 0, 0, radius, base, top, density, coverage, 0.3f,
                1f, 0f, 0f, "test", 0.5f, anvil, 0.5f, storm, 0f, 0f, 1);
    }

    /** A mature cumulonimbus capillatus (Look 0.95 / 0.95, anvil 0.8, storm 0.6). */
    private static CloudShape storm(double x, double z) {
        return cloud(x, z, 800, 200, 2600, 0.95f, 0.95f, 0.8f, 0.6f);
    }

    /** A fair-weather cumulus mediocris (Look 0.65 / 0.85, no storm darkness). */
    private static CloudShape cumulus(double x, double z) {
        return cloud(x, z, 150, 200, 480, 0.65f, 0.85f, 0f, 0f);
    }

    private static StormShade.Sample at(List<CloudShape> clouds, double x, double z) {
        return StormShade.sample(new StormShade.Index(clouds, 0), x, z);
    }

    @Test
    void aClearSkyIsClear() {
        assertEquals(StormShade.Sample.CLEAR, at(List.of(), 0, 0));
        assertEquals(1.0, StormShade.lightFactor(0), 1e-9);
    }

    @Test
    void underAStormItIsDarkerThanVanillaThunder() {
        StormShade.Sample s = at(List.of(storm(0, 0)), 0, 0);
        assertTrue(s.cover() > 0.99, "the sun is gone: " + s);
        assertTrue(s.overhead() > 0.85, "gloomy overhead: " + s);
        // Vanilla's full thunderstorm leaves about 0.47 of the day's light.
        assertTrue(StormShade.lightFactor(s.darkness()) < 0.45, "darker than vanilla thunder: " + s);
        assertEquals(1 - StormShade.MAX_DIM, StormShade.lightFactor(1), 1e-9);
    }

    @Test
    void aFairCumulusHidesTheSunButBarelyDims() {
        StormShade.Sample s = at(List.of(cumulus(0, 0)), 0, 0);
        assertTrue(s.cover() > 0.6, "its shadow hides the sun: " + s);
        assertTrue(s.darkness() < 0.25, "but the day stays bright: " + s);
        assertTrue(StormShade.lightFactor(s.darkness()) > 0.88);
    }

    @Test
    void walkingOutFromUnderAStormIsGradual() {
        List<CloudShape> clouds = List.of(storm(0, 0));
        StormShade.Index index = new StormShade.Index(clouds, 0);
        double last = StormShade.sample(index, 0, 0).darkness();
        for (int x = 1; x <= 2600; x++) {
            double d = StormShade.sample(index, x, 0).darkness();
            assertTrue(Math.abs(d - last) < 0.01, "no step at x=" + x + ": " + last + " -> " + d);
            last = d;
        }
        assertTrue(last < 0.2, "and well out from under it, it is light again: " + last);
    }

    @Test
    void aNearbyStormDimsTheDayBeforeItArrives() {
        // 1200 blocks off: beyond its anvil, so nothing overhead, but in the rings around.
        StormShade.Sample near = at(List.of(storm(1200 + 1800, 0)), 0, 0);
        StormShade.Sample nearer = at(List.of(storm(1900, 0)), 0, 0);
        assertTrue(near.overhead() < 0.01, "nothing overhead: " + near);
        assertTrue(nearer.overhead() < 0.01, "nothing overhead: " + nearer);
        assertTrue(nearer.around() > 0.05, "but gloom around: " + nearer);
        assertTrue(nearer.darkness() > near.darkness(), "darker as it nears");
        StormShade.Sample far = at(List.of(storm(8000, 0)), 0, 0);
        assertEquals(0, far.darkness(), 1e-9, "a storm far off leaves the day alone");
    }

    @Test
    void aFormingStormShadesLess() {
        UUID id = UUID.randomUUID();
        CloudShape young = new CloudShape(id, id, "minecraft:overworld", 0, 0, 0, 0, 0, 800, 200, 2600, 0.95f, 0.95f,
                0.3f, 0.2f, 0f, 0f, "test", 0.5f, 0.8f, 0.5f, 0.6f, 0f, 0f, 1);
        assertTrue(at(List.of(young), 0, 0).darkness() < at(List.of(storm(0, 0)), 0, 0).darkness());
    }
}
