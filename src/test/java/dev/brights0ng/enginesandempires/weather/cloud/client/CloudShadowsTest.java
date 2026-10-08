package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/** Clouds shading the clouds below them (2026-10-07, Bright: realistic strength). */
class CloudShadowsTest {

    static CloudShape deck(CloudType t, double x, double z, float r, float base, float top) {
        CloudType.Look look = t.look;
        return new CloudShape(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", x, z, 0, 0, 0, r, base, top,
                look.density(), look.coverage(), look.edgeSoftness(), 1, 0, 0, t.id, 0, 0, 0, 0, 0, 0, 1);
    }

    @Test
    void aCumulusUnderARainDeckGoesGrey() {
        CloudShape ns = deck(CloudType.NIMBOSTRATUS, 0, 0, 1000, 250, 900);
        CloudShape sc = deck(CloudType.STRATOCUMULUS, 5000, 0, 800, 300, 400);
        CloudShadows s = CloudShadows.of(List.of(ns, sc), 0);
        double[] under = s.light(0, 150, 0, Set.of());
        double[] thin = s.light(5000, 150, 0, Set.of());
        double[] clear = s.light(-20_000, 150, 0, Set.of());
        System.out.printf("under nimbostratus: sky %.2f sun %.2f; under stratocumulus: sky %.2f sun %.2f%n",
                under[0], under[1], thin[0], thin[1]);
        assertEquals(1, clear[0], 1e-9);
        assertTrue(under[0] < 0.5 && under[1] < 0.1, "grey and sunless under a rain deck");
        assertTrue(thin[0] > under[0] && thin[0] < 0.95, "dimmed under a thin deck");
        // A cloud doesn't shade itself, nor what is above it.
        assertEquals(1, s.light(0, 150, 0, Set.of(ns.id()))[0], 1e-9);
        assertEquals(1, s.light(0, 1000, 0, Set.of())[0], 1e-9);
    }
}
