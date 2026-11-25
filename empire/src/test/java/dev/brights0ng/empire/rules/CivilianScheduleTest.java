// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.CivilianPreferences.Activity;
import dev.brights0ng.empire.rules.CivilianSchedule.Anchor;
import dev.brights0ng.empire.rules.CivilianSchedule.AnchorKind;
import dev.brights0ng.empire.rules.CivilianSchedule.Decision;
import dev.brights0ng.empire.rules.CivilianSchedule.PlanContext;
import dev.brights0ng.empire.rules.CivilianSchedule.SkeletonInput;
import dev.brights0ng.empire.rules.CivilianSchedule.SlotKind;
import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

class CivilianScheduleTest {

    private static final UUID FARMER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void farmerWalkthroughCoversLateMealShiftWorkQuotaSocialAtPubAndStalestHobby() {
        CivilianSchedule schedule = new CivilianSchedule();
        CrowdMemory crowd = new CrowdMemory();
        crowd.recordSighting(LocationKey.FOOD_SITE, 9L, ScheduleClock.mmss(14, 0));

        // 8-minute sleeper with 4:30 remaining tonight (wakes at 04:30, bedtime 16:00)
        CivilianTraits traits = CivilianTraits.NEUTRAL;
        CivilianDerivedStats stats = traits.derivedStats();
        schedule.buildDailySkeleton(new SkeletonInput(
                FARMER_ID, 10L, 0,
                ScheduleClock.mmss(8, 0), ScheduleClock.mmss(3, 30), false,
                traits, stats, 1.0, 0.0, 0.75, 0.2,
                true, ScheduleClock.mmss(7, 0), crowd));

        // Verify skeleton anchors: ContinueSleep (00:00..04:30), Lunch, Dinner, TonightSleep (16:00..20:00)
        assertEquals(4, schedule.anchors().size());
        assertEquals(AnchorKind.CONTINUE_SLEEP, schedule.anchors().get(0).kind());
        assertEquals(ScheduleClock.mmss(4, 30), schedule.anchors().get(0).endTick());
        assertEquals(AnchorKind.TONIGHT_SLEEP, schedule.anchors().get(3).kind());
        assertEquals(ScheduleClock.tonightBedtimeTick(ScheduleClock.mmss(8, 0)), schedule.anchors().get(3).startTick());

        // Read yesterday (day 9), never fished -> FISHING is stalest hobby
        schedule.recordHobbyPerformed(Activity.READING, 9L);

        int[] workToFoodTravel = {ScheduleClock.mmss(0, 15)};
        CivilianSchedule.TravelEstimator travel = (from, to) -> {
            if (from == to) return 0;
            if ((from == LocationKey.WORK && to == LocationKey.FOOD_SITE)
                    || (from == LocationKey.FOOD_SITE && to == LocationKey.WORK)) {
                return workToFoodTravel[0];
            }
            if ((from == LocationKey.HOBBY_FISHING && to == LocationKey.HOME)
                    || (from == LocationKey.HOME && to == LocationKey.HOBBY_FISHING)) {
                return ScheduleClock.mmss(0, 30);
            }
            return ScheduleClock.mmss(0, 15);
        };

        // Wakes at 04:30 -> ContinueSleep completes -> picks WORK until Lunch - 15s
        schedule.completeAnchor(0);
        Anchor lunch = schedule.anchors().get(1);
        Decision morning = schedule.decideNextSlot(ctx(10L, ScheduleClock.mmss(4, 30), LocationKey.HOME,
                true, true, false, true, List.of(Activity.FISHING, Activity.READING),
                1.0, 0.0, traits, travel, crowd));
        assertEquals(SlotKind.WORK, morning.slot().kind());
        assertEquals(lunch.startTick() - ScheduleClock.mmss(0, 15), morning.slot().departAtTick());
        assertTrue(morning.mustTravelFirst());

        // Works until departAt, then creeper hole makes walk take 30s -> arrives 15s late at Lunch
        workToFoodTravel[0] = ScheduleClock.mmss(0, 30);
        int arrivedLunch = lunch.startTick() + ScheduleClock.mmss(0, 15);
        Anchor shiftedLunch = schedule.onArriveAtAnchor(1, arrivedLunch, ScheduleClock.mmss(0, 30));
        assertEquals(arrivedLunch, shiftedLunch.startTick());
        assertEquals(arrivedLunch + CivilianSchedule.MEAL_DURATION_TICKS, shiftedLunch.endTick());

        // Finishes Lunch and finishes remaining work quota
        schedule.completeAnchor(1);
        schedule.addWorkDoneTicks(schedule.workQuotaTicks());

        // After work quota finishes (30s + buffer before Dinner), socializes at FOOD_SITE (seen occupied in last 3d)
        Anchor dinner = schedule.anchors().get(2);
        int afterWork = dinner.startTick() - ScheduleClock.mmss(1, 0);
        Decision social = schedule.decideNextSlot(ctx(10L, afterWork, LocationKey.WORK,
                true, true, false, false, List.of(Activity.FISHING, Activity.READING),
                1.0, 0.0, traits, travel, crowd));
        assertEquals(SlotKind.SOCIALIZE, social.slot().kind());
        assertEquals(LocationKey.FOOD_SITE, social.destination());
        assertEquals(dinner.startTick(), social.slot().departAtTick());

        // Social quota finishes, Dinner completes -> picks stalest liked hobby (FISHING) until bedtime - 30s
        schedule.addSocialDoneTicks(schedule.socialQuotaTicks());
        schedule.completeAnchor(2);
        int afterDinner = dinner.endTick();
        Anchor sleep = schedule.anchors().get(3);
        Decision hobby = schedule.decideNextSlot(ctx(10L, afterDinner, LocationKey.FOOD_SITE,
                true, true, false, true, List.of(Activity.FISHING, Activity.READING),
                1.0, 0.0, traits, travel, crowd));
        assertEquals(SlotKind.HOBBY, hobby.slot().kind());
        assertEquals(Activity.FISHING, hobby.slot().hobby());
        assertEquals(sleep.startTick() - ScheduleClock.mmss(0, 30), hobby.slot().departAtTick());

        // At bedtime - 30s, departs for HOME to sleep
        Decision toBed = schedule.decideNextSlot(ctx(10L, sleep.startTick() - ScheduleClock.mmss(0, 30),
                LocationKey.HOBBY_FISHING, true, true, false, false, List.of(Activity.FISHING, Activity.READING),
                1.0, 0.0, traits, travel, crowd));
        assertEquals(SlotKind.SLEEP, toBed.slot().kind());
        assertEquals(LocationKey.HOME, toBed.destination());
    }

    @Test
    void outdoorWindowBlocksPreDawnOutdoorWorkAndHobbies() {
        CivilianSchedule schedule = new CivilianSchedule();
        CrowdMemory crowd = new CrowdMemory();
        CivilianTraits traits = CivilianTraits.NEUTRAL;
        // Short sleeper wakes at 02:30 (well before 04:00 outdoor window)
        schedule.buildDailySkeleton(new SkeletonInput(
                FARMER_ID, 5L, ScheduleClock.mmss(2, 30),
                ScheduleClock.mmss(5, 0), ScheduleClock.mmss(5, 0), true,
                traits, traits.derivedStats(), 1.0, 0.0, 0.75, 1.0,
                true, ScheduleClock.mmss(7, 0), crowd));
        schedule.addSocialDoneTicks(schedule.socialQuotaTicks());

        CivilianSchedule.TravelEstimator travel = (a, b) -> a == b ? 0 : 100;
        // At 02:30, outdoor WORK and outdoor FISHING are blocked -> picks indoor READING!
        Decision preDawn = schedule.decideNextSlot(ctx(5L, ScheduleClock.mmss(2, 30), LocationKey.HOME,
                true, true, false, true, List.of(Activity.FISHING, Activity.READING),
                1.0, 0.0, traits, travel, crowd));
        assertEquals(SlotKind.HOBBY, preDawn.slot().kind());
        assertEquals(Activity.READING, preDawn.slot().hobby());

        // At 04:00, outdoor WORK unlocks!
        Decision dawn = schedule.decideNextSlot(ctx(5L, ScheduleClock.mmss(4, 0), LocationKey.HOME,
                true, true, false, true, List.of(Activity.FISHING, Activity.READING),
                1.0, 0.0, traits, travel, crowd));
        assertEquals(SlotKind.WORK, dawn.slot().kind());
    }

    @Test
    void minimumUsefulDurationPreventsMicroTrips() {
        CivilianSchedule schedule = new CivilianSchedule();
        CrowdMemory crowd = new CrowdMemory();
        CivilianTraits traits = CivilianTraits.NEUTRAL;
        schedule.buildDailySkeleton(new SkeletonInput(
                FARMER_ID, 3L, ScheduleClock.mmss(5, 0),
                ScheduleClock.mmss(6, 0), ScheduleClock.mmss(6, 0), true,
                traits, traits.derivedStats(), 1.0, 0.0, 0.75, 1.0,
                true, ScheduleClock.mmss(7, 0), crowd));
        schedule.addSocialDoneTicks(schedule.socialQuotaTicks());

        Anchor lunch = schedule.anchors().get(0); // first uncompleted is LUNCH
        assertEquals(AnchorKind.LUNCH, lunch.kind());
        // Travel to WORK = 600t (30s), WORK -> FOOD_SITE = 600t (30s), timeLeft = 1,240t (62s) -> only 2s usable (< 30s!)
        CivilianSchedule.TravelEstimator slowTravel = (a, b) -> {
            if (a == b) return 0;
            if (a == LocationKey.HOME && b == LocationKey.FOOD_SITE) return 200;
            return 600;
        };
        Decision d = schedule.decideNextSlot(ctx(3L, lunch.startTick() - 1_240, LocationKey.HOME,
                true, false, false, true, List.of(Activity.FISHING),
                1.0, 0.0, traits, slowTravel, crowd));
        assertEquals(SlotKind.RELAX, d.slot().kind());
    }

    @Test
    void extraversionPullsMealTimesTowardCrowdPeakWhileSolitaryDriftsAway() {
        UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        int peak = ScheduleClock.mmss(9, 40);
        CivilianTraits neutral = CivilianTraits.NEUTRAL;
        CivilianTraits chatterbox = neutral.with(BaseTrait.EXTRAVERSION, 95);
        CivilianTraits solitary = neutral.with(BaseTrait.EXTRAVERSION, -95);

        int neutralLunch = CivilianSchedule.computeMealStartTick(id, 1L, CivilianSchedule.BASE_LUNCH_TICK,
                peak, neutral, neutral.derivedStats(), 0x11A4_BEEFL);
        int extraLunch = CivilianSchedule.computeMealStartTick(id, 1L, CivilianSchedule.BASE_LUNCH_TICK,
                peak, chatterbox, chatterbox.derivedStats(), 0x11A4_BEEFL);
        int solitaryLunch = CivilianSchedule.computeMealStartTick(id, 1L, CivilianSchedule.BASE_LUNCH_TICK,
                peak, solitary, solitary.derivedStats(), 0x11A4_BEEFL);

        assertTrue(Math.abs(extraLunch - peak) < Math.abs(neutralLunch - peak),
                "high Extraversion pulls closer to crowd peak: extra=" + extraLunch + " neutral=" + neutralLunch + " peak=" + peak);
        assertTrue(Math.abs(solitaryLunch - peak) > Math.abs(extraLunch - peak),
                "Solitary stays farther from crowd peak than Chatterbox");
    }

    @Test
    void lowFulfilmentShrinksWorkQuotaAndTriggersBoundedPreemptiveBreak() {
        CivilianTraits lazy = CivilianTraits.NEUTRAL
                .with(BaseTrait.CONSCIENTIOUSNESS, -90)
                .with(BaseTrait.AGREEABLENESS, -80)
                .with(BaseTrait.NEUROTICISM, 70);
        CrowdMemory crowd = new CrowdMemory();

        CivilianSchedule happySched = new CivilianSchedule();
        happySched.buildDailySkeleton(new SkeletonInput(
                FARMER_ID, 2L, ScheduleClock.mmss(5, 0),
                ScheduleClock.mmss(6, 0), ScheduleClock.mmss(6, 0), true,
                lazy, lazy.derivedStats(), 1.0, 0.0, 0.75, 1.0,
                true, ScheduleClock.mmss(7, 0), crowd));

        CivilianSchedule unhappySched = new CivilianSchedule();
        unhappySched.buildDailySkeleton(new SkeletonInput(
                FARMER_ID, 2L, ScheduleClock.mmss(5, 0),
                ScheduleClock.mmss(6, 0), ScheduleClock.mmss(6, 0), true,
                lazy, lazy.derivedStats(), 0.15, 0.85, 0.75, 1.0,
                true, ScheduleClock.mmss(7, 0), crowd));

        assertTrue(unhappySched.workQuotaTicks() < happySched.workQuotaTicks(),
                "discontent shrinks committed work quota: unhappy=" + unhappySched.workQuotaTicks()
                        + " happy=" + happySched.workQuotaTicks());

        // With high discontent + lazy traits, preemptive break triggers within a few rolls and is capped in [0.5h, 2.0h]
        unhappySched.addSocialDoneTicks(unhappySched.socialQuotaTicks());
        CivilianSchedule.TravelEstimator fastTravel = (a, b) -> a == b ? 0 : 100;
        boolean sawPreempt = false;
        for (long day = 1; day <= 10 && !sawPreempt; day++) {
            CivilianSchedule s = new CivilianSchedule();
            s.buildDailySkeleton(new SkeletonInput(
                    FARMER_ID, day, ScheduleClock.mmss(5, 0),
                    ScheduleClock.mmss(6, 0), ScheduleClock.mmss(6, 0), true,
                    lazy, lazy.derivedStats(), 0.10, 0.90, 0.75, 1.0,
                    true, ScheduleClock.mmss(7, 0), crowd));
            s.addSocialDoneTicks(s.socialQuotaTicks());
            Decision d = s.decideNextSlot(ctx(day, ScheduleClock.mmss(5, 0), LocationKey.HOME,
                    true, false, false, true, List.of(Activity.READING),
                    0.10, 0.90, lazy, fastTravel, crowd));
            if (d.slot().preemptiveBreak()) {
                sawPreempt = true;
                assertEquals(SlotKind.HOBBY, d.slot().kind());
                assertTrue(d.slot().preemptiveCapTicks() >= CivilianSchedule.PREEMPT_MIN_TICKS
                        && d.slot().preemptiveCapTicks() <= CivilianSchedule.PREEMPT_MAX_TICKS);
                // After the capped break finishes in the same gap, the citizen returns to WORK (never infinite hobby)
                Decision afterBreak = s.decideNextSlot(ctx(day, ScheduleClock.mmss(6, 30), LocationKey.HOBBY_READING,
                        true, false, false, true, List.of(Activity.READING),
                        0.10, 0.90, lazy, fastTravel, crowd));
                assertEquals(SlotKind.WORK, afterBreak.slot().kind());
            }
        }
        assertTrue(sawPreempt, "expected low-fulfilment lazy citizen to take a bounded preemptive break");
    }

    @Test
    void saveRestoreAndCopyAreIndependent() {
        CivilianSchedule s = new CivilianSchedule();
        s.buildDailySkeleton(new SkeletonInput(
                FARMER_ID, 7L, ScheduleClock.mmss(5, 0),
                ScheduleClock.mmss(6, 0), ScheduleClock.mmss(6, 0), true,
                CivilianTraits.NEUTRAL, CivilianTraits.NEUTRAL.derivedStats(),
                1.0, 0.0, 0.75, 0.5, true, ScheduleClock.mmss(7, 0), new CrowdMemory()));
        s.addWorkDoneTicks(1_200);
        s.recordHobbyPerformed(Activity.FISHING, 7L);

        CivilianSchedule copy = s.copy();
        assertEquals(s.save(), copy.save());
        copy.addWorkDoneTicks(600);
        assertNotEquals(s.save(), copy.save());
        assertEquals(1_200, s.workDoneTicks());
    }

    private static PlanContext ctx(long day, int nowTick, LocationKey here,
                                   boolean hasWork, boolean workOutdoor,
                                   boolean nearbySocial, boolean comfortable,
                                   List<Activity> hobbies, double fulfilment, double grievance,
                                   CivilianTraits traits, CivilianSchedule.TravelEstimator travel,
                                   CrowdMemory crowd) {
        return new PlanContext(
                FARMER_ID, day, nowTick, here,
                hasWork, workOutdoor, nearbySocial, comfortable,
                hobbies, fulfilment, grievance, 0.75,
                traits, travel, crowd, loc -> true
        );
    }
}
