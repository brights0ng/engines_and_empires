package dev.brights0ng.enginesandempires.weather.lightning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class LightningModelTest {

    @Test
    void aFullStormFlashesAboutOnceAMinute() {
        // Over a minute (1200 ticks) at strength 1: 1 - 1/e that at least one comes.
        assertEquals(1 - Math.exp(-1), LightningModel.chance(1, 1, 1200), 1e-9);
        // Checked once a second, about 1 in 60.
        assertEquals(1.0 / 60, LightningModel.chance(1, 1, 20), 3e-4);
        // A half-strength storm, about half as often; none from a calm cloud.
        assertEquals(LightningModel.chance(1, 1, 20) / 2, LightningModel.chance(0.5, 1, 20), 1e-4);
        assertEquals(0, LightningModel.chance(0, 1, 20));
    }

    @Test
    void aQuarterStrikeTheGround() {
        SplittableRandom rng = new SplittableRandom(1);
        int ground = 0;
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            if (LightningModel.kind(rng.nextDouble(), 0.25) == LightningModel.Kind.GROUND) {
                ground++;
            }
        }
        assertEquals(0.25, ground / (double) n, 0.01);
    }

    @Test
    void flashesStayInTheStormAndCrowdItsMiddle() {
        List<LightningModel.Dome> domes = List.of(
                new LightningModel.Dome(0, 0, 200),
                new LightningModel.Dome(500, 0, 200),
                new LightningModel.Dome(-500, 0, 200));
        SplittableRandom rng = new SplittableRandom(7);
        int middle = 0;
        double nearCentre = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            LightningModel.Point p = LightningModel.where(domes, 0, 0, 700, 200, 900, rng);
            assertTrue(p.y() >= 200 + 700 * 0.25 - 1e-9 && p.y() <= 200 + 700 * 0.75 + 1e-9, "middle of the cloud");
            boolean inside = domes.stream().anyMatch(d -> Math.hypot(p.x() - d.x(), p.z() - d.z()) <= d.radius());
            assertTrue(inside, "inside a tower");
            if (Math.hypot(p.x(), p.z()) <= 200) {
                middle++;
                nearCentre += Math.hypot(p.x(), p.z());
            }
        }
        // The middle tower gets more than a third (it is nearest the storm's centre)...
        assertTrue(middle > n / 3, "middle tower " + middle);
        // ...and within a tower, points crowd its middle (uniform-area would average 2/3 of the reach).
        assertTrue(nearCentre / middle < 0.5 * 0.85 * 200 + 5, "mean distance " + nearCentre / middle);
    }

    @Test
    void noTowersNoFlash() {
        assertNull(LightningModel.where(List.of(), 0, 0, 100, 100, 200, new SplittableRandom(1)));
    }

    @Test
    void theTallestThingNearbyTakesTheStrike() {
        List<LightningModel.Column> flat = List.of(
                new LightningModel.Column(0, 0, 70),
                new LightningModel.Column(4, 0, 70),
                new LightningModel.Column(8, 0, 90));
        // A 20-block tower 8 blocks away beats the flat ground right under the flash.
        assertEquals(8, LightningModel.tallest(flat, 0, 0).x());
        // Of two equal heights, the nearer.
        List<LightningModel.Column> even = List.of(
                new LightningModel.Column(6, 0, 70),
                new LightningModel.Column(2, 0, 70));
        assertEquals(2, LightningModel.tallest(even, 0, 0).x());
        // A block higher isn't worth going 12 blocks for.
        List<LightningModel.Column> slight = List.of(
                new LightningModel.Column(0, 0, 70),
                new LightningModel.Column(12, 0, 71));
        assertEquals(0, LightningModel.tallest(slight, 0, 0).x());
        assertNull(LightningModel.tallest(List.of(), 0, 0));
    }
}
