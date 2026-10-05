package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SizeProfileTest {

    @Test
    void drawsStayWithinTheLimitsAndCentreOnTheMedian() {
        SizeProfile profile = new SizeProfile(300, 0.35, 100, 500);
        DepositRandom rng = new DepositRandom(99);
        int[] draws = new int[5000];
        for (int i = 0; i < draws.length; i++) {
            draws[i] = profile.draw(rng);
            assertTrue(draws[i] >= 100 && draws[i] <= 500);
        }
        Arrays.sort(draws);
        assertEquals(300, draws[draws.length / 2], 15);
        assertEquals(100, draws[0], 0, "the floor should be reached");
        assertEquals(500, draws[draws.length - 1], 0, "the cap should be reached");
    }

    @Test
    void zeroSigmaAlwaysGivesTheMedian() {
        SizeProfile profile = new SizeProfile(250, 0.0, 10, 1000);
        DepositRandom rng = new DepositRandom(5);
        for (int i = 0; i < 100; i++) {
            assertEquals(250, profile.draw(rng));
        }
    }

    @Test
    void invalidProfilesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SizeProfile(300, 0.3, 0, 500));
        assertThrows(IllegalArgumentException.class, () -> new SizeProfile(300, 0.3, 400, 500));
        assertThrows(IllegalArgumentException.class, () -> new SizeProfile(600, 0.3, 100, 500));
        assertThrows(IllegalArgumentException.class, () -> new SizeProfile(300, -1.0, 100, 500));
    }
}
