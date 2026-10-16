package dev.brights0ng.enginesandempires.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FoodClimateTest {

    private static final FoodClimate.Rules R = FoodClimate.Rules.DEFAULTS;
    private static final SpoilTimes T = SpoilTimes.DEFAULTS;
    private static final long DAY = SpoilTimes.DAY;

    @Test
    void theRateDoublesEveryTenDegrees() {
        assertEquals(1, R.rate(20), 1e-12);
        assertEquals(2, R.rate(30), 1e-12);
        assertEquals(0.5, R.rate(10), 1e-12);
        assertEquals(Math.pow(2, 1.5), R.rate(35), 1e-12);
    }

    @Test
    void aCellarDoublesTheTimeAHotRoomShortensIt() {
        long now = 100 * DAY;
        FoodFreshness food = FoodFreshness.born(now - DAY, 4);
        // A day at 10 C ages it half a day: its birth moves half a day later
        FoodFreshness cool = FoodClimate.apply(food, now, DAY, 10, false, R, T);
        assertEquals(now - DAY / 2, cool.cohorts().get(0).born());
        // A day at 30 C ages it two days
        FoodFreshness hot = FoodClimate.apply(food, now, DAY, 30, false, R, T);
        assertEquals(now - 2 * DAY, hot.cohorts().get(0).born());
        // At 20 C nothing changes
        assertEquals(food.cohorts().get(0).born(), FoodClimate.apply(food, now, DAY, 20, false, R, T).cohorts().get(0).born());
    }

    @Test
    void neverYoungerThanBrandNew() {
        long now = 100 * DAY;
        FoodFreshness food = FoodFreshness.born(now - 10, 1);
        assertEquals(now, FoodClimate.apply(food, now, DAY, -40, false, R, T).cohorts().get(0).born());
    }

    @Test
    void freezingHasAMarginAndThawingCostsHalfAStage() {
        long now = 100 * DAY;
        FoodFreshness food = FoodFreshness.born(now - DAY, 2);
        // -1 C: not below -2, so it stays thawed (and ages at 2^-2.1)
        assertFalse(FoodClimate.apply(food, now, 0, -1, false, R, T).cohorts().get(0).frozen());
        FoodFreshness frozen = FoodClimate.apply(food, now, 0, -5, false, R, T);
        assertTrue(frozen.cohorts().get(0).frozen());
        // Back to +1 C: still frozen (not above +2)
        assertTrue(FoodClimate.apply(frozen, now, 0, 1, false, R, T).cohorts().get(0).frozen());
        // +5 C: thaws, aged by half a stage (3 days)
        FoodFreshness thawed = FoodClimate.apply(frozen, now, 0, 5, false, R, T);
        assertFalse(thawed.cohorts().get(0).frozen());
        assertEquals(now - DAY - 3 * DAY, thawed.cohorts().get(0).born());
    }

    @Test
    void frozenFoodNearlyStops() {
        long now = 100 * DAY;
        FoodFreshness food = new FoodFreshness(java.util.List.of(new Cohort(now - 20 * DAY, 1, true)), 0);
        FoodFreshness after = FoodClimate.apply(food, now, 10 * DAY, -20, false, R, T);
        // Ten days frozen age it half a day: its birth moves 9.5 days later
        assertEquals(now - 20 * DAY + Math.round(0.95 * 10 * DAY), after.cohorts().get(0).born());
    }

    @Test
    void theIceChestKeepsItFrozenWhateverTheAir() {
        long now = 100 * DAY;
        FoodFreshness food = FoodFreshness.born(now - DAY, 1);
        FoodFreshness chilled = FoodClimate.apply(food, now, DAY, 30, true, R, T);
        assertTrue(chilled.cohorts().get(0).frozen());
        assertEquals(now - DAY + Math.round(0.95 * DAY), chilled.cohorts().get(0).born());
    }

    @Test
    void frozenAndThawedGroupsOfOneStageStayApart() {
        long now = 100 * DAY;
        FoodFreshness mixed = new FoodFreshness(java.util.List.of(new Cohort(now - DAY, 3, false),
                new Cohort(now - DAY, 2, true)), 0).settle(now, T);
        assertEquals(2, mixed.cohorts().size());
        assertFalse(mixed.cohorts().get(0).frozen());
        assertTrue(mixed.cohorts().get(1).frozen());
    }

    @Test
    void anUnknownTemperatureChangesNothing() {
        long now = 100 * DAY;
        FoodFreshness food = FoodFreshness.born(now - DAY, 1);
        assertEquals(food, FoodClimate.apply(food, now, DAY, Double.NaN, false, R, T));
    }
}
