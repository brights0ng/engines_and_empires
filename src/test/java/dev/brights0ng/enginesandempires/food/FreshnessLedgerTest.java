package dev.brights0ng.enginesandempires.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.food.FreshnessLedger.Place;

/** Putting freshness back where units went, from before-and-after counts. */
class FreshnessLedgerTest {

    private static final SpoilTimes T = new SpoilTimes(1000, 1000, 1000);
    private static final long NOW = 100_000;
    private static final String BREAD = "bread";
    private static final String NAMED_BREAD = "bread named Loaf";

    private static final FoodFreshness FRESH_10 = FoodFreshness.born(NOW - 100, 10);
    private static final FoodFreshness STALE_5 = FoodFreshness.born(NOW - 2500, 5);

    private static Place had(Object kind, FoodFreshness freshness) {
        return new Place(kind, freshness.total(), freshness);
    }

    private static Place has(Object kind, int count) {
        return new Place(kind, count, FoodFreshness.EMPTY);
    }

    private static FoodFreshness[] settle(List<Place> before, List<Place> after) {
        return FreshnessLedger.settle(before, after, NOW, T);
    }

    /** Fresh bread from the cursor placed onto stale bread: the slot holds both groups, the cursor is empty. */
    @Test
    void placingKeepsBothGroups() {
        FoodFreshness[] r = settle(List.of(had(BREAD, STALE_5), had(BREAD, FRESH_10)), List.of(has(BREAD, 15), Place.NOTHING));
        assertEquals(List.of(new Cohort(NOW - 100, 10), new Cohort(NOW - 2500, 5)), r[0].cohorts());
        assertNull(r[1]);
    }

    /** Dragging 10 fresh over a slot of 5 stale and an empty slot: the stale slot does not come out fresh. */
    @Test
    void draggingCannotLaunderRottenFood() {
        FoodFreshness[] r = settle(
                List.of(had(BREAD, STALE_5), Place.NOTHING, had(BREAD, FRESH_10)),
                List.of(has(BREAD, 10), has(BREAD, 5), Place.NOTHING));
        assertEquals(List.of(new Cohort(NOW - 100, 5), new Cohort(NOW - 2500, 5)), r[0].cohorts());
        assertEquals(List.of(new Cohort(NOW - 100, 5)), r[1].cohorts());
    }

    /** Picking up half: the cursor gets the top units, the slot keeps the rest. */
    @Test
    void pickingUpHalfTakesTheTop() {
        FoodFreshness mixed = FRESH_10.merge(STALE_5, NOW, T);
        FoodFreshness[] r = settle(List.of(had(BREAD, mixed), Place.NOTHING), List.of(has(BREAD, 7), has(BREAD, 8)));
        assertEquals(List.of(new Cohort(NOW - 100, 2), new Cohort(NOW - 2500, 5)), r[0].cohorts());
        assertEquals(List.of(new Cohort(NOW - 100, 8)), r[1].cohorts());
    }

    /** Swapping two different kinds (a named loaf for plain bread) keeps each stack's own freshness. */
    @Test
    void kindsDoNotMix() {
        FoodFreshness[] r = settle(
                List.of(had(BREAD, STALE_5), had(NAMED_BREAD, FRESH_10)),
                List.of(has(NAMED_BREAD, 10), has(BREAD, 5)));
        assertEquals(FRESH_10.settle(NOW, T), r[0]);
        assertEquals(STALE_5.settle(NOW, T), r[1]);
    }

    @Test
    void unitsFromNowhereAreBornNow() {
        FoodFreshness[] r = settle(List.of(Place.NOTHING), List.of(has(BREAD, 3)));
        assertEquals(List.of(new Cohort(NOW, 3)), r[0].cohorts());
    }

    @Test
    void unchangedPlacesAreLeftAlone() {
        FoodFreshness[] r = settle(List.of(had(BREAD, STALE_5), had(BREAD, FRESH_10)), List.of(has(BREAD, 5), has(BREAD, 10)));
        assertNull(r[0]);
        assertNull(r[1]);
    }

    /** Two trades shift-clicked in one go: the second result was minted mid-click, and its freshness is what arrives. */
    @Test
    void mintedFoodIsDrawnBeforeAnythingIsBornNow() {
        FoodFreshness firstTrade = FoodFreshness.born(NOW - 1500, 6);
        FoodFreshness secondTrade = FoodFreshness.born(NOW - 200, 6);
        FoodFreshness[] r = FreshnessLedger.settle(
                List.of(had(BREAD, firstTrade), Place.NOTHING),
                List.of(Place.NOTHING, has(BREAD, 12)),
                List.of(had(BREAD, secondTrade)), NOW, T);
        assertEquals(List.of(new Cohort(NOW - 200, 6), new Cohort(NOW - 1500, 6)), r[1].cohorts());
    }
}
