// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class CatchUpMathTest {

    @Test
    void lambdaIsVanillasChancePerBlock() {
        // 4096 ticks at speed 3: each block expects 3 random ticks.
        assertEquals(3.0, CatchUpMath.lambda(4096, 3), 1e-12);
        assertEquals(0.0, CatchUpMath.lambda(0, 3));
        assertEquals(0.0, CatchUpMath.lambda(-5, 3));
        assertEquals(0.0, CatchUpMath.lambda(10_000, 0), "random ticking off gives nothing back");
    }

    @Test
    void zeroMeanGivesNoTicks() {
        assertEquals(0, CatchUpMath.ticks(0.0, CatchUpMath.CROP_CAP, () -> 0.999));
        assertEquals(0, CatchUpMath.ticks(-1.0, CatchUpMath.CROP_CAP, () -> 0.999));
    }

    @Test
    void theCapHolds() {
        assertEquals(CatchUpMath.CROP_CAP, CatchUpMath.ticks(1e9, CatchUpMath.CROP_CAP, () -> 0.0));
        assertEquals(CatchUpMath.TREE_CAP, CatchUpMath.ticks(500.0, CatchUpMath.TREE_CAP, () -> 0.5));
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            assertTrue(CatchUpMath.ticks(40.0, 16, random::nextDouble) <= 16);
        }
    }

    @Test
    void inversionPicksTheRightValue() {
        // lambda 1: P(0) = 0.3679, P(<=1) = 0.7358, P(<=2) = 0.9197.
        assertEquals(0, CatchUpMath.ticks(1.0, 64, () -> 0.30));
        assertEquals(1, CatchUpMath.ticks(1.0, 64, () -> 0.50));
        assertEquals(2, CatchUpMath.ticks(1.0, 64, () -> 0.80));
        assertEquals(3, CatchUpMath.ticks(1.0, 64, () -> 0.95));
    }

    @Test
    void theMeanMatchesLambdaWellBelowTheCap() {
        Random random = new Random(42);
        int draws = 20_000;
        long sum = 0;
        for (int i = 0; i < draws; i++) {
            sum += CatchUpMath.ticks(5.0, 64, random::nextDouble);
        }
        assertEquals(5.0, (double) sum / draws, 0.1);
    }
}
