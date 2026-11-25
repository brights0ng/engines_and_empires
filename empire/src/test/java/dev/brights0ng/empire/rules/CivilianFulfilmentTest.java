// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.CivilianPreferences.Activity;

class CivilianFulfilmentTest {

    private static final double EPS = 1e-5;

    @Test
    void preferencesRollDistinctCountsCenteredOnTwoToThree() {
        Random rng = new Random(42L);
        int twoOrThreeFoods = 0;
        int twoOrThreeActs = 0;
        int trials = 1000;
        for (int i = 0; i < trials; i++) {
            CivilianPreferences prefs = CivilianPreferences.roll(rng, CivilianPreferences.DEFAULT_FOOD_POOL);
            int fc = prefs.favouriteFoods().size();
            int ac = prefs.favouriteActivities().size();
            assertTrue(fc >= 1 && fc <= 4, "food count " + fc);
            assertTrue(ac >= 1 && ac <= 4, "activity count " + ac);
            assertEquals(fc, new HashSet<>(prefs.favouriteFoods()).size(), "distinct foods");
            assertEquals(ac, new HashSet<>(prefs.favouriteActivities()).size(), "distinct activities");
            if (fc == 2 || fc == 3) twoOrThreeFoods++;
            if (ac == 2 || ac == 3) twoOrThreeActs++;
        }
        // Expected 75% (6/8) in {2, 3}.
        assertTrue(twoOrThreeFoods > 650 && twoOrThreeFoods < 850, "twoOrThreeFoods=" + twoOrThreeFoods);
        assertTrue(twoOrThreeActs > 650 && twoOrThreeActs < 850, "twoOrThreeActs=" + twoOrThreeActs);

        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        assertEquals(CivilianPreferences.defaultFor(id), CivilianPreferences.defaultFor(id));
        for (Activity a : Activity.values()) {
            assertEquals(a, Activity.byId(a.id()));
        }
        assertEquals(Activity.READING, Activity.byId("reading"));
        assertEquals(null, Activity.byId("pub_chat"));
    }

    @Test
    void opennessBiasesPreferenceCountDistribution() {
        int trials = 2_000;
        Random rngLow = new Random(77L);
        Random rngHigh = new Random(77L);
        double sumLow = 0;
        double sumHigh = 0;
        for (int i = 0; i < trials; i++) {
            sumLow += CivilianPreferences.roll(rngLow, CivilianPreferences.DEFAULT_FOOD_POOL, -1.0).favouriteFoods().size();
            sumHigh += CivilianPreferences.roll(rngHigh, CivilianPreferences.DEFAULT_FOOD_POOL, 1.0).favouriteFoods().size();
        }
        double meanLow = sumLow / trials;
        double meanHigh = sumHigh / trials;
        assertTrue(meanLow > 1.8 && meanLow < 2.2, "low openness mean=" + meanLow);
        assertTrue(meanHigh > 2.8 && meanHigh < 3.2, "high openness mean=" + meanHigh);
    }

    @Test
    void rollingThreeDayWindowsExpireAfterThreeActiveDays() {
        CivilianFulfilment f = new CivilianFulfilment(new CivilianPreferences(List.of("minecraft:bread"), List.of(Activity.WALK)));
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        // Day 0
        assertTrue(f.recordNearbyPerson(p1));
        assertFalse(f.recordNearbyPerson(p1)); // duplicate same day
        assertTrue(f.recordSocialInteraction(p1, 100L));
        assertFalse(f.recordSocialInteraction(p1, 500L)); // within 1200-tick cooldown
        assertTrue(f.recordSocialInteraction(p2, 500L)); // different partner allowed
        assertTrue(f.recordSocialInteraction(p1, 1300L)); // after cooldown
        f.recordLikedMeal();
        f.recordLikedHobbyTicks(1_200L);
        assertEquals(1, f.distinctCommunityPeople());
        assertEquals(3, f.socializingCount());
        assertEquals(1, f.likedMealsCount());
        assertEquals(1_200L, f.likedHobbyTicksCount());

        // Day 1: p1 seen again + p2 new
        f.advanceDay();
        f.recordNearbyPerson(p1);
        f.recordNearbyPerson(p2);
        f.recordLikedMeal();
        f.recordLikedHobbyTicks(600L);
        assertEquals(2, f.distinctCommunityPeople());
        assertEquals(3, f.socializingCount());
        assertEquals(2, f.likedMealsCount());
        assertEquals(1_800L, f.likedHobbyTicksCount());

        // Day 2: p3 new
        f.advanceDay();
        f.recordNearbyPerson(p3);
        assertEquals(3, f.distinctCommunityPeople());
        assertEquals(3, f.socializingCount());
        assertEquals(2, f.likedMealsCount());
        assertEquals(1_800L, f.likedHobbyTicksCount());

        // Day 3: Day 0 drops out (socializing 3 -> 0, liked meal on day 0 drops -> 1, hobby 1200 drops -> 600)
        f.advanceDay();
        assertEquals(3, f.distinctCommunityPeople());
        assertEquals(0, f.socializingCount());
        assertEquals(1, f.likedMealsCount());
        assertEquals(600L, f.likedHobbyTicksCount());

        // Day 4: Day 1 drops out -> only p3 remains, liked meals 0, hobby ticks 0
        f.advanceDay();
        assertEquals(1, f.distinctCommunityPeople());
        assertEquals(0, f.likedMealsCount());
        assertEquals(0L, f.likedHobbyTicksCount());
    }

    @Test
    void comfortRingBufferCapsAtOneDayAndAverages() {
        CivilianFulfilment f = new CivilianFulfilment(new CivilianPreferences(List.of("minecraft:bread"), List.of(Activity.WALK)));
        assertFalse(f.hasComfortSamples());
        assertTrue(Double.isNaN(f.averageComfort()));

        for (int i = 0; i < CivilianFulfilment.COMFORT_DAY_SLOTS; i++) {
            f.recordComfortSample(0.0F);
        }
        assertEquals(20, f.comfortSampleCount());
        assertEquals(0.0, f.averageComfort(), EPS);

        // Overwrite half of the ring with 1.0 -> average should be 0.5
        for (int i = 0; i < 10; i++) {
            f.recordComfortSample(1.0F);
        }
        assertEquals(20, f.comfortSampleCount());
        assertEquals(0.5, f.averageComfort(), EPS);
    }

    @Test
    void saveRestoreAndCopyAreIndependent() {
        CivilianPreferences prefs = new CivilianPreferences(List.of("minecraft:apple", "minecraft:bread"), List.of(Activity.FISHING));
        CivilianFulfilment f = new CivilianFulfilment(prefs);
        f.setCitizenActiveTicks(30_000L);
        f.recordNearbyPerson(UUID.randomUUID());
        f.recordSocialInteraction(UUID.randomUUID(), 200L);
        f.recordLikedMeal();
        f.recordLikedHobbyTicks(1_500L);
        f.recordComfortSample(0.8F);
        f.setGrievance(0.35F);

        CivilianFulfilment copy = f.copy();
        assertEquals(f.save(), copy.save());

        copy.recordLikedMeal();
        copy.recordLikedHobbyTicks(600L);
        copy.setGrievance(0.9F);
        assertNotEquals(f.save(), copy.save());
        assertEquals(1, f.likedMealsCount());
        assertEquals(1_500L, f.likedHobbyTicksCount());
        assertEquals(0.35F, f.grievance(), EPS);
    }

    @Test
    void empireSafetyTrackerDeduplicatesTicksAndExpiresAfterThreeDays() {
        EmpireSafetyTracker tracker = new EmpireSafetyTracker();
        tracker.recordHit();
        tracker.recordHit();
        assertEquals(2, tracker.hitsLast3Days());

        // Same gameTime tick does not double-advance
        tracker.countActiveTickIfNew(100L);
        tracker.countActiveTickIfNew(100L);
        assertEquals(1L, tracker.activeTicks());

        // Advance 3 full days (3 * 24000 ticks)
        for (long t = 1; t < 3 * CivilianFood.DAY_LENGTH; t++) {
            tracker.countActiveTick();
        }
        assertEquals(0, tracker.hitsLast3Days());

        tracker.recordHit();
        EmpireSafetyTracker restored = EmpireSafetyTracker.restore(tracker.save());
        assertEquals(tracker.save(), restored.save());
        assertEquals(1, restored.hitsLast3Days());
    }

    @Test
    void foodActiveClockSignalsRolloverForFulfilment() {
        CivilianFood food = new CivilianFood();
        int rollovers = 0;
        for (int i = 0; i < CivilianFood.DAY_LENGTH * 2; i++) {
            if (food.countActiveTick()) {
                rollovers++;
            }
        }
        assertEquals(2, rollovers);
    }

    @Test
    void travellerWantMatchesOrInjectsFavouriteFood() {
        Random rng = new Random(99L);
        List<String> matching = List.of("minecraft:apple", "minecraft:bread");
        int idx = TravellerWants.pickMatching(rng, matching);
        assertTrue(idx >= 0);
        assertEquals("minecraft:bread", TravellerWants.WANTS.get(idx).itemId());

        List<String> noMatch = List.of("minecraft:apple", "minecraft:carrot");
        assertEquals(-1, TravellerWants.pickMatching(rng, noMatch));

        CivilianPreferences prefs = new CivilianPreferences(noMatch, List.of(Activity.WALK));
        CivilianPreferences updated = prefs.withFavouriteFood("minecraft:cooked_cod");
        assertEquals(2, updated.favouriteFoods().size());
        assertTrue(updated.likesFood("minecraft:cooked_cod"));
        assertEquals(updated, updated.withFavouriteFood("minecraft:cooked_cod"));
    }
}
