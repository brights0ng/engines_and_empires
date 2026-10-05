package dev.brights0ng.enginesandempires.frontier.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HabitationTest {

    private static final TierParams P = TierParams.DEFAULTS;
    private static final int DAY = TierParams.DAY;

    @Test
    void keepsItsValueThroughTheGrace() {
        assertEquals(20_000, Habitation.effective(20_000, 1000, 1000 + P.graceTicks(), P));
    }

    @Test
    void thenLosesADaysWorthPerDay() {
        long lastSeen = 5000;
        assertEquals(20_000 - DAY / 2, Habitation.effective(20_000, lastSeen, lastSeen + P.graceTicks() + DAY / 2, P));
        assertEquals(0, Habitation.effective(20_000, lastSeen, lastSeen + P.graceTicks() + DAY, P));
    }

    @Test
    void nothingStoredStaysNothing() {
        assertEquals(0, Habitation.effective(0, Habitation.NEVER, 1_000_000, P));
        assertEquals(0, Habitation.effective(0, 10, 10, P));
    }

    @Test
    void addingFirstLetsWhatFadedGo() {
        long lastSeen = 0;
        long now = P.graceTicks() + DAY / 4;
        assertEquals(10_000 - DAY / 4 + 100, Habitation.add(10_000, lastSeen, now, 100, P));
    }

    @Test
    void aSectionBanksAtMostTheCap() {
        assertEquals(P.habitationCap(), Habitation.add(P.habitationCap() - 10, 0, 0, 1000, P));
    }

    @Test
    void aBaseLivedInForMonthsStillCoolsOffInAWeekAndAHalf() {
        // At the cap, a section is back to nothing a day and a half after the grace runs out.
        long leftAt = 1_000_000;
        assertTrue(Habitation.effective(P.habitationCap(), leftAt, leftAt + P.graceTicks() + DAY, P) > 0);
        assertEquals(0, Habitation.effective(P.habitationCap(), leftAt, leftAt + P.graceTicks() + DAY * 3 / 2, P));
    }

    @Test
    void residentsCountForTheGrace() {
        assertTrue(Habitation.recentResident(100, 100 + P.graceTicks(), P));
        assertFalse(Habitation.recentResident(100, 101 + P.graceTicks(), P));
        assertFalse(Habitation.recentResident(Habitation.NEVER, 0, P));
    }

    @Test
    void aCrowdCountsTheSameAsOnePerson() {
        // Counted every 20 ticks: the first person in a stretch gets the 20 ticks, anyone else the time since.
        assertEquals(20, Habitation.elapsedSince(1000, 1020, 20));
        assertEquals(0, Habitation.elapsedSince(1020, 1020, 20), "a second person the same tick adds nothing");
        assertEquals(5, Habitation.elapsedSince(1015, 1020, 20), "someone counted 5 ticks after the last adds 5");
        assertEquals(20, Habitation.elapsedSince(0, 100_000, 20), "coming back after a long time adds one stretch, not the gap");
        assertEquals(20, Habitation.elapsedSince(Habitation.NEVER, 5, 20));

        // Twelve villagers counted at different moments across a stretch add up to exactly the stretch.
        long last = 1000;
        long total = 0;
        for (int v = 1; v <= 12; v++) {
            long now = 1000 + v * 20L / 12;
            total += Habitation.elapsedSince(last, now, 20);
            last = Math.max(last, now);
        }
        assertEquals(20, total);
    }
}
