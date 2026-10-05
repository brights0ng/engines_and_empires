package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DepthProfileTest {

    private final DepthProfile mixed = new DepthProfile(-40, 60);

    @Test
    void depositsAtOrAboveTheOriginAreNormalSize() {
        for (int y = 0; y <= 60; y += 5) {
            assertEquals(1.0, mixed.sizeMultiplier(y, 0.0), 0.0);
            assertEquals(1.0, mixed.sizeMultiplier(y, 0.999), 0.0);
        }
    }

    @Test
    void fullyDeepDepositsGetTheDrawnMultiplierWithinTheRange() {
        assertEquals(4.0, mixed.sizeMultiplier(-DepthProfile.RAMP, 0.0), 1.0e-9);
        assertEquals(8.0, mixed.sizeMultiplier(-DepthProfile.RAMP, 1.0), 1.0e-9);
        assertEquals(6.0, mixed.sizeMultiplier(-40, 0.5), 1.0e-9);
        for (double roll = 0.0; roll < 1.0; roll += 0.05) {
            double m = mixed.sizeMultiplier(-30, roll);
            assertTrue(m >= 4.0 && m <= 8.0, "multiplier " + m);
        }
    }

    @Test
    void thereIsNoJumpAtTheOrigin() {
        assertEquals(1.0, mixed.sizeMultiplier(0, 1.0), 1.0e-9);
        assertTrue(mixed.sizeMultiplier(-1, 1.0) < 1.5, "one block below the origin is barely bigger");
    }

    @Test
    void growthIsMonotonicWithDepth() {
        double previous = 1.0;
        for (int y = 0; y >= -DepthProfile.RAMP; y--) {
            double m = mixed.sizeMultiplier(y, 0.7);
            assertTrue(m >= previous - 1.0e-12, "multiplier fell at y = " + y);
            previous = m;
        }
    }

    @Test
    void heightsAreDrawnUniformlyIncludingBothEnds() {
        DepositRandom rng = new DepositRandom(1234);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 20000; i++) {
            int y = mixed.pickY(rng);
            min = Math.min(min, y);
            max = Math.max(max, y);
            assertTrue(y >= -40 && y <= 60);
        }
        assertEquals(-40, min);
        assertEquals(60, max);
    }

    @Test
    void maxMultiplierIsOneOnlyIfNothingIsBelowTheOrigin() {
        assertEquals(8.0, mixed.maxMultiplier(), 0.0);
        assertEquals(1.0, new DepthProfile(0, 100).maxMultiplier(), 0.0);
        assertEquals(1.0, new DepthProfile(20, 150).maxMultiplier(), 0.0);
    }

    @Test
    void deepThroughoutMakesEveryHeightFullyDeep() {
        DepthProfile nether = DepthProfile.deepThroughout(10, 110);
        assertEquals(10, nether.minY());
        assertEquals(110, nether.maxY());
        assertEquals(8.0, nether.maxMultiplier(), 0.0);
        for (int y = 10; y <= 110; y++) {
            assertEquals(4.0, nether.sizeMultiplier(y, 0.0), 1.0e-9, "y=" + y);
            assertEquals(6.0, nether.sizeMultiplier(y, 0.5), 1.0e-9, "y=" + y);
            assertEquals(8.0, nether.sizeMultiplier(y, 1.0), 1.0e-9, "y=" + y);
        }
    }

    @Test
    void customOriginsMoveWhereDepositsStartGrowing() {
        DepthProfile profile = new DepthProfile(0, 100, 4.0, 8.0, 50);
        assertEquals(1.0, profile.sizeMultiplier(50, 1.0), 0.0);
        assertEquals(1.0, profile.sizeMultiplier(80, 1.0), 0.0);
        assertEquals(8.0, profile.sizeMultiplier(50 - DepthProfile.RAMP, 1.0), 1.0e-9);
        assertEquals(8.0, profile.sizeMultiplier(0, 1.0), 1.0e-9);
    }

    @Test
    void invalidProfilesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DepthProfile(50, 10));
        assertThrows(IllegalArgumentException.class, () -> new DepthProfile(-64, 10));   // inside the bedrock
        assertThrows(IllegalArgumentException.class, () -> new DepthProfile(0, 400));    // above the world
        assertThrows(IllegalArgumentException.class, () -> new DepthProfile(0, 10, 0.5, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new DepthProfile(0, 10, 4.0, 2.0));
    }
}
