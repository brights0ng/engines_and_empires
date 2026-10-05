package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.geophone.ReaderAccuracy.Confidence;

class ReaderAccuracyTest {

    // ---- spreadOf ----

    @Test
    void identicalPositionsHaveNoSpread() {
        assertEquals(0.0, ReaderAccuracy.spreadOf(new double[]{5, 5, 5}, new double[]{9, 9, 9}), 1.0e-9);
    }

    @Test
    void oneOrNoPositionsHaveNoSpread() {
        assertEquals(0.0, ReaderAccuracy.spreadOf(new double[0], new double[0]), 0.0);
        assertEquals(0.0, ReaderAccuracy.spreadOf(new double[]{3}, new double[]{4}), 0.0);
    }

    @Test
    void spreadIsTheTypicalDistanceFromTheCentre() {
        // Four points each exactly 10 blocks from their shared centre, along the axes.
        double spread = ReaderAccuracy.spreadOf(new double[]{10, -10, 0, 0}, new double[]{0, 0, 10, -10});
        assertEquals(10.0, spread, 1.0e-9);
    }

    @Test
    void spreadIgnoresHeightEntirely() {
        // Only x and z are given; y never enters the calculation because the parameter list has no room for it.
        double spread = ReaderAccuracy.spreadOf(new double[]{0, 20}, new double[]{0, 0});
        assertEquals(10.0, spread, 1.0e-9);
    }

    // ---- maxErrorFor ----

    @Test
    void eachShotsWorstCaseIsTheRangeOfTheShotBelowIt() {
        assertEquals(ReaderAccuracy.LOWEST_MAX_ERROR, ReaderAccuracy.maxErrorFor(SeismicShots.HAMMER_RANGE), 0.0, "hammer on the ground");
        assertEquals(16.0, ReaderAccuracy.maxErrorFor(SeismicShots.PLATE_RANGE), 0.0, "hammer on a plate");
        assertEquals(128.0, ReaderAccuracy.maxErrorFor(SeismicShots.MECHANICAL_RANGE), 0.0, "mechanical thumper");
        assertEquals(512.0, ReaderAccuracy.maxErrorFor(CombustiveFiring.FUEL_RANGE), 0.0, "combustive, gasoline or biodiesel");
        assertEquals(512.0, ReaderAccuracy.maxErrorFor(CombustiveFiring.PREMIUM_RANGE), 0.0,
                "combustive, diesel or volatile: same rung as gasoline, so it steps down to the mechanical thumper too");
        assertEquals(16.0, ReaderAccuracy.maxErrorFor(CombustiveFiring.DUD_RANGE), 0.0, "a combustive dud is a plate's thump");
    }

    // ---- errorRadius ----

    @Test
    void aTightArrayGetsTheWorstCaseAndNeverWorse() {
        assertEquals(128.0, ReaderAccuracy.errorRadius(0.0, 128.0), 1.0e-9);
        assertEquals(128.0, ReaderAccuracy.errorRadius(ReaderAccuracy.FULL_ERROR_SPREAD, 128.0), 1.0e-9);
        for (double spread = 0; spread < 100; spread += 0.5) {
            assertTrue(ReaderAccuracy.errorRadius(spread, 128.0) <= 128.0);
        }
    }

    @Test
    void spreadingTheArrayOutShrinksTheError() {
        double spread = ReaderAccuracy.FULL_ERROR_SPREAD;
        assertEquals(64.0, ReaderAccuracy.errorRadius(2 * spread, 128.0), 1.0e-9, "twice as wide, half the error");
        assertTrue(ReaderAccuracy.errorRadius(12, 16.0) < ReaderAccuracy.errorRadius(6, 16.0));
    }

    // ---- confidenceFor ----

    @Test
    void confidenceTiersAreOrderedFromWorstToBest() {
        assertTrue(Confidence.UNKNOWN.ordinal() < Confidence.ROUGH.ordinal());
        assertTrue(Confidence.ROUGH.ordinal() < Confidence.APPROXIMATE.ordinal());
        assertTrue(Confidence.APPROXIMATE.ordinal() < Confidence.PRECISE.ordinal());
    }

    @Test
    void confidenceMatchesTheStatedThresholdsExactly() {
        assertEquals(Confidence.PRECISE, ReaderAccuracy.confidenceFor(0.0));
        assertEquals(Confidence.PRECISE, ReaderAccuracy.confidenceFor(ReaderAccuracy.PRECISE_MAX_BLOCKS));
        assertEquals(Confidence.APPROXIMATE, ReaderAccuracy.confidenceFor(Math.nextUp(ReaderAccuracy.PRECISE_MAX_BLOCKS)));
        assertEquals(Confidence.APPROXIMATE, ReaderAccuracy.confidenceFor(ReaderAccuracy.APPROXIMATE_MAX_BLOCKS));
        assertEquals(Confidence.ROUGH, ReaderAccuracy.confidenceFor(Math.nextUp(ReaderAccuracy.APPROXIMATE_MAX_BLOCKS)));
        assertEquals(Confidence.ROUGH, ReaderAccuracy.confidenceFor(10000.0));
    }

    // ---- arraySeed ----

    @Test
    void theSameArrayGivesTheSameSeedHoweverItIsListed() {
        long forward = ReaderAccuracy.arraySeed(7, new long[]{1, 2, 3});
        long backward = ReaderAccuracy.arraySeed(7, new long[]{3, 2, 1});
        assertEquals(forward, backward);
    }

    @Test
    void aDifferentDepositOrArrayGivesADifferentSeed() {
        long base = ReaderAccuracy.arraySeed(7, new long[]{1, 2, 3});
        assertTrue(base != ReaderAccuracy.arraySeed(8, new long[]{1, 2, 3}), "a different deposit");
        assertTrue(base != ReaderAccuracy.arraySeed(7, new long[]{1, 2, 4}), "a different geophone in the array");
        assertTrue(base != ReaderAccuracy.arraySeed(7, new long[]{1, 2, 3, 4}), "an extra geophone in the array");
    }

    @Test
    void arraySeedDoesNotModifyTheArrayItWasGiven() {
        long[] keys = {5, 1, 3};
        ReaderAccuracy.arraySeed(9, keys);
        assertEquals(5, keys[0], "the caller's array must not be sorted in place");
        assertEquals(1, keys[1]);
        assertEquals(3, keys[2]);
    }

    // ---- jitterOffset ----

    @Test
    void noErrorMeansNoOffset() {
        assertArrayZero(ReaderAccuracy.jitterOffset(12345L, 0.0));
        assertArrayZero(ReaderAccuracy.jitterOffset(12345L, -5.0));
    }

    @Test
    void theSameSeedAndRadiusAlwaysGiveTheSameOffset() {
        int[] first = ReaderAccuracy.jitterOffset(999L, 40.0);
        int[] second = ReaderAccuracy.jitterOffset(999L, 40.0);
        assertArrayEquals(first, second);
    }

    @Test
    void theOffsetNeverExceedsItsOwnRadius() {
        for (long seed = 0; seed < 500; seed++) {
            double radius = 30.0;
            int[] offset = ReaderAccuracy.jitterOffset(seed, radius);
            double horizontal = Math.sqrt((double) offset[0] * offset[0] + (double) offset[2] * offset[2]);
            assertTrue(horizontal <= radius + 1.0, "seed " + seed + " gave a horizontal offset of " + horizontal + " for a radius of " + radius);
            assertTrue(Math.abs(offset[1]) <= radius * ReaderAccuracy.VERTICAL_FACTOR + 1.0,
                    "seed " + seed + " gave a vertical offset of " + offset[1]);
        }
    }

    @Test
    void differentSeedsUsuallyGiveDifferentOffsets() {
        int[] first = ReaderAccuracy.jitterOffset(1L, 40.0);
        boolean anyDifferent = false;
        for (long seed = 2; seed < 20; seed++) {
            if (!java.util.Arrays.equals(first, ReaderAccuracy.jitterOffset(seed, 40.0))) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "twenty different seeds gave the exact same offset, which is far too unlikely to be a coincidence");
    }

    @Test
    void aRadiusSmallerThanHalfABlockAlwaysRoundsToZero() {
        for (long seed = 0; seed < 50; seed++) {
            assertArrayZero(ReaderAccuracy.jitterOffset(seed, 0.4));
        }
    }

    private static void assertArrayZero(int[] offset) {
        assertArrayEquals(new int[]{0, 0, 0}, offset);
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i);
        }
    }
}
