package dev.brights0ng.enginesandempires.frontier.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.frontier.deep.Disturbance.Outcome;

class DisturbanceTest {

    private static final int NEEDED = 5;
    private static final int WINDOW = 1200;
    private static final int COOLDOWN = 36000;
    private static final double MECHANICAL = 0.10;
    private static final double COMBUSTIVE = 0.25;

    private static Outcome shot(Disturbance d, int cx, int cz, long now, ShotSource source, double roll) {
        return d.record(cx, cz, now, source, NEEDED, WINDOW, COOLDOWN, MECHANICAL, COMBUSTIVE, roll);
    }

    @Test
    void fiveShotsInAMinuteRollForAnIncursion() {
        Disturbance d = new Disturbance();
        for (int i = 0; i < 4; i++) {
            assertEquals(Outcome.NONE, shot(d, 0, 0, i * 200L, ShotSource.MECHANICAL, 0.0));
        }
        assertEquals(Outcome.INCURSION, shot(d, 0, 0, 800, ShotSource.MECHANICAL, 0.05), "0.05 is under 10%");
    }

    @Test
    void aMissedRollStartsTheCountOverWithoutACooldown() {
        Disturbance d = new Disturbance();
        for (int i = 0; i < 4; i++) {
            shot(d, 0, 0, i, ShotSource.MECHANICAL, 0.5);
        }
        assertEquals(Outcome.MISSED, shot(d, 0, 0, 4, ShotSource.MECHANICAL, 0.5), "0.5 misses 10%");
        assertFalse(d.isQuiet(0, 0, 5), "a miss leaves no cooldown");
        assertEquals(0, d.countAround(0, 0, 5, WINDOW), "the count starts over");
        for (int i = 0; i < 4; i++) {
            assertEquals(Outcome.NONE, shot(d, 0, 0, 10 + i, ShotSource.MECHANICAL, 0.0));
        }
        assertEquals(Outcome.INCURSION, shot(d, 0, 0, 20, ShotSource.MECHANICAL, 0.0), "the next five roll again");
    }

    @Test
    void combustiveShotsAreLikelierToCallOne() {
        assertEquals(0.10, Disturbance.chance(5, 0, MECHANICAL, COMBUSTIVE), 1e-9);
        assertEquals(0.25, Disturbance.chance(5, 5, MECHANICAL, COMBUSTIVE), 1e-9);
        assertEquals(0.19, Disturbance.chance(5, 3, MECHANICAL, COMBUSTIVE), 1e-9);

        Disturbance d = new Disturbance();
        for (int i = 0; i < 4; i++) {
            shot(d, 0, 0, i, ShotSource.COMBUSTIVE, 0.2);
        }
        assertEquals(Outcome.INCURSION, shot(d, 0, 0, 5, ShotSource.COMBUSTIVE, 0.2), "0.2 is under 25%");
    }

    @Test
    void shotsOlderThanTheWindowDoNotCount() {
        Disturbance d = new Disturbance();
        for (int i = 0; i < 4; i++) {
            shot(d, 0, 0, i * 400L, ShotSource.MECHANICAL, 0.0); // 0, 400, 800, 1200
        }
        assertEquals(Outcome.NONE, shot(d, 0, 0, 1300, ShotSource.MECHANICAL, 0.0), "the shot at 0 has left the window");
        assertEquals(Outcome.INCURSION, shot(d, 0, 0, 1350, ShotSource.MECHANICAL, 0.0));
    }

    @Test
    void spreadingThumpersAcrossNeighbouringChunksDoesNotDodgeIt() {
        Disturbance d = new Disturbance();
        shot(d, -1, -1, 0, ShotSource.MECHANICAL, 0.0);
        shot(d, 1, 0, 10, ShotSource.MECHANICAL, 0.0);
        shot(d, 0, 1, 20, ShotSource.MECHANICAL, 0.0);
        shot(d, 1, 1, 30, ShotSource.MECHANICAL, 0.0);
        assertEquals(Outcome.INCURSION, shot(d, 0, 0, 40, ShotSource.MECHANICAL, 0.0), "all within the 3x3 around (0, 0)");
    }

    @Test
    void thumpersTwoChunksApartAreCountedApart() {
        Disturbance d = new Disturbance();
        for (int i = 0; i < 4; i++) {
            shot(d, 0, 0, i, ShotSource.MECHANICAL, 0.0);
        }
        assertEquals(Outcome.NONE, shot(d, 3, 0, 10, ShotSource.MECHANICAL, 0.0));
    }

    @Test
    void dryFiresCountForNothing() {
        Disturbance d = new Disturbance();
        for (int i = 0; i < 10; i++) {
            assertEquals(Outcome.NONE, shot(d, 0, 0, i, ShotSource.COMBUSTIVE_DRY, 0.0));
        }
        assertEquals(0, d.countAround(0, 0, 10, WINDOW));
    }

    @Test
    void afterACallTheAreaIsQuietForTheCooldown() {
        Disturbance d = new Disturbance();
        for (int i = 0; i < 5; i++) {
            shot(d, 0, 0, i, ShotSource.MECHANICAL, 0.0);
        }
        assertTrue(d.isQuiet(1, 1, 10), "the whole 3x3 goes quiet");
        for (int i = 0; i < 20; i++) {
            assertEquals(Outcome.NONE, shot(d, 0, 0, 100 + i, ShotSource.MECHANICAL, 0.0));
        }
        assertFalse(d.isQuiet(0, 0, 4 + COOLDOWN));
        long later = COOLDOWN + 10_000L;
        for (int i = 0; i < 4; i++) {
            assertEquals(Outcome.NONE, shot(d, 0, 0, later + i, ShotSource.MECHANICAL, 0.0));
        }
        assertEquals(Outcome.INCURSION, shot(d, 0, 0, later + 4, ShotSource.MECHANICAL, 0.0), "after the cooldown it can happen again");
    }
}
