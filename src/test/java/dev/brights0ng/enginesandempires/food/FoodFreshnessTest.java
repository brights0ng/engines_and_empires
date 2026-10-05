package dev.brights0ng.enginesandempires.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The stacking rules on their own: stages, merging within a stage, keeping stages apart, and taking from the top. */
class FoodFreshnessTest {

    private static final SpoilTimes T = new SpoilTimes(1000, 1000, 1000);
    private static final long NOW = 100_000;

    /** Born {@code age} ticks before NOW. */
    private static Cohort aged(long age, int count) {
        return new Cohort(NOW - age, count);
    }

    @Test
    void stagesAndSubstages() {
        assertEquals(FoodStage.FRESH, T.stage(0));
        assertEquals(FoodStage.FRESH, T.stage(999));
        assertEquals(FoodStage.RIPE, T.stage(1000));
        assertEquals(FoodStage.STALE, T.stage(2500));
        assertEquals(FoodStage.ROTTING, T.stage(3000));
        assertEquals(FoodStage.ROTTING, T.stage(1_000_000));
        assertEquals(FoodStage.FRESH, T.stage(-50), "a clock that went backwards counts as brand new");
        assertEquals(0, T.substage(0));
        assertEquals(1, T.substage(100));
        assertEquals(9, T.substage(999));
        assertEquals(0, T.substage(1000));
        assertEquals(0, T.substage(5000));
        assertEquals(30, T.step(5000));
        assertEquals(19, T.step(1999));
    }

    /** Bread at substage 2 on bread at substage 10, both fresh: all of it becomes the older one. */
    @Test
    void sameStageTakesTheOlderAge() {
        FoodFreshness settled = new FoodFreshness(List.of(aged(150, 5), aged(950, 7)), 0).settle(NOW, T);
        assertEquals(List.of(aged(950, 12)), settled.cohorts());
    }

    /** 21 fresh on 9 stale: one stack, two groups, freshest first and on top. */
    @Test
    void differentStagesStayApart() {
        FoodFreshness settled = new FoodFreshness(List.of(aged(2500, 9), aged(100, 21)), 0).settle(NOW, T);
        assertEquals(List.of(aged(100, 21), aged(2500, 9)), settled.cohorts());
        assertEquals(30, settled.total());
    }

    @Test
    void aPickedTopFollowsItsStage() {
        FoodFreshness staleOnTop = new FoodFreshness(List.of(aged(100, 21), aged(2500, 9)), 1);
        FoodFreshness withRipeAdded = staleOnTop.merge(FoodFreshness.born(NOW - 1500, 3), NOW, T);
        assertEquals(2, withRipeAdded.top(), "stale is still on top, now third");
    }

    @Test
    void byDefaultTheFreshestIsOnTop() {
        FoodFreshness staleOnly = FoodFreshness.born(NOW - 2500, 9);
        FoodFreshness withFresh = staleOnly.merge(FoodFreshness.born(NOW - 100, 21), NOW, T);
        assertEquals(0, withFresh.top());
        assertEquals(aged(100, 21), withFresh.topCohort());
    }

    @Test
    void scrollingWrapsRoundTheGroups() {
        FoodFreshness three = new FoodFreshness(List.of(aged(100, 1), aged(1500, 2), aged(2500, 3)), 0);
        assertEquals(1, three.rotateTop(1).top());
        assertEquals(2, three.rotateTop(1).rotateTop(1).top());
        assertEquals(0, three.rotateTop(1).rotateTop(1).rotateTop(1).top());
        assertEquals(2, three.rotateTop(-1).top());
        assertEquals(0, FoodFreshness.born(NOW, 5).rotateTop(1).top(), "one group has nothing to scroll to");
    }

    @Test
    void alwaysRottingFoodIsAgedToRotting() {
        FoodFreshness flesh = FoodFreshness.born(NOW, 4).atLeastAge(T.start(FoodStage.ROTTING), NOW, T);
        assertEquals(FoodStage.ROTTING, T.stage(NOW - flesh.topCohort().born()));
    }

    @Test
    void takingFromAPickedTopKeepsItOnTop() {
        FoodFreshness staleOnTop = new FoodFreshness(List.of(aged(100, 21), aged(2500, 9)), 1);
        FoodFreshness.Split split = staleOnTop.takeTop(12, NOW, T);
        assertEquals(List.of(aged(100, 3), aged(2500, 9)), split.taken().cohorts());
        assertEquals(1, split.taken().top(), "the taken part still has stale on top");
        assertEquals(0, split.rest().top());
    }

    @Test
    void cohortsThatAgeIntoOneStageMerge() {
        FoodFreshness stack = new FoodFreshness(List.of(aged(800, 4), aged(1100, 6)), 0).settle(NOW, T);
        assertEquals(2, stack.cohorts().size());
        FoodFreshness later = stack.settle(NOW + 300, T);
        assertEquals(List.of(new Cohort(NOW - 1100, 10)), later.cohorts(), "both ripe now, so they share the older age");
    }

    @Test
    void takingFromTheTopTakesTheTopGroupFirst() {
        FoodFreshness stack = new FoodFreshness(List.of(aged(100, 21), aged(2500, 9)), 0);
        FoodFreshness.Split split = stack.takeTop(25, NOW, T);
        assertEquals(List.of(aged(100, 21), aged(2500, 4)), split.taken().cohorts());
        assertEquals(List.of(aged(2500, 5)), split.rest().cohorts());
    }

    @Test
    void takingFromAStaleTopTakesStaleFirst() {
        FoodFreshness stack = new FoodFreshness(List.of(aged(100, 21), aged(2500, 9)), 1);
        FoodFreshness.Split split = stack.takeTop(5, NOW, T);
        assertEquals(List.of(aged(2500, 5)), split.taken().cohorts());
        assertEquals(List.of(aged(100, 21), aged(2500, 4)), split.rest().cohorts());
        assertEquals(1, split.rest().top(), "stale is still on top of what is left");
    }

    @Test
    void theSafeGuessesOnlyEverMakeFoodStaler() {
        FoodFreshness stack = new FoodFreshness(List.of(aged(100, 21), aged(2500, 9)), 0);
        assertEquals(List.of(aged(100, 16), aged(2500, 9)), stack.reconcile(25, NOW, T).cohorts(), "shrinking loses fresh units");
        assertEquals(List.of(aged(100, 21), aged(2500, 12)), stack.reconcile(33, NOW, T).cohorts(), "growing adds stale units");
        assertEquals(List.of(new Cohort(NOW, 3)), FoodFreshness.EMPTY.reconcile(3, NOW, T).cohorts(), "nothing known: born now");
    }

    @Test
    void mergingKeepsTheReceiversTop() {
        FoodFreshness receiver = new FoodFreshness(List.of(aged(100, 3), aged(2500, 2)), 1);
        FoodFreshness incoming = FoodFreshness.born(NOW - 1200, 4);
        FoodFreshness merged = receiver.merge(incoming, NOW, T);
        assertEquals(List.of(aged(100, 3), aged(1200, 4), aged(2500, 2)), merged.cohorts());
        assertEquals(2, merged.top());
        assertTrue(FoodFreshness.EMPTY.merge(receiver, NOW, T).top() == 1, "an empty receiver takes the incoming top");
    }
}
