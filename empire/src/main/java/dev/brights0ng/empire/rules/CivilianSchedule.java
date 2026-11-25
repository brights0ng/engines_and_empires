// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.CivilianPreferences.Activity;
import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

/**
 * Pure Level-1 daily scheduler for Empire citizens: sparse midnight-anchored Anchor Skeleton plus live Gap-Filler
 * (zero Minecraft imports).
 *
 * <p>Clock convention: {@link ScheduleClock} ({@code 00:00} = midnight = vanilla {@code 18,000}; {@code 20:00} = next
 * midnight). Hard anchors ({@code CONTINUE_SLEEP}, {@code LUNCH}, {@code DINNER}, {@code TONIGHT_SLEEP}) are generated
 * lazily whenever the schedule day rolls over (handling midnight, night skips, and unloaded days). All gaps between
 * anchors are planned live at decision points using learned travel times ({@link RouteMemory}), minimum useful
 * durations, the {@code 04:00..16:00} outdoor window, daily work/social quotas, and bounded low-fulfilment pre-work
 * breaks ({@code 0.5..2.0} MC hours).
 */
public final class CivilianSchedule {

    /** Sentinel for a schedule that has not yet been built for any day. */
    public static final long NO_DAY = Long.MIN_VALUE;

    /** PROVISIONAL: base Lunch anchor start (09:00 = 10,800t, just before noon 10:00). */
    public static final int BASE_LUNCH_TICK = ScheduleClock.mmss(9, 0);
    /** PROVISIONAL: base Dinner anchor start (14:00 = 16,800t, just before sunset 15:00). */
    public static final int BASE_DINNER_TICK = ScheduleClock.mmss(14, 0);
    /** PROVISIONAL: scheduled meal duration (0:15 = 300t; ends early once full or out of food). */
    public static final int MEAL_DURATION_TICKS = ScheduleClock.mmss(0, 15);
    /** PROVISIONAL: maximum personal habit offset around base mealtime (±0:45 = ±900t). */
    public static final int MEAL_PERSONAL_OFFSET_MAX_TICKS = ScheduleClock.mmss(0, 45);
    /** PROVISIONAL: maximum deterministic daily jitter on mealtime (±0:05 = ±100t). */
    public static final int MEAL_DAILY_JITTER_TICKS = ScheduleClock.mmss(0, 5);
    /** PROVISIONAL: maximum total shift away from base mealtime after Extraversion push/pull (±1:00 = ±1,200t). */
    public static final int MEAL_MAX_SHIFT_TICKS = ScheduleClock.mmss(1, 0);
    /** PROVISIONAL: default daily work ask when a citizen has an assigned workplace (7:00 = 8,400t). */
    public static final int DEFAULT_WORK_ASK_TICKS = ScheduleClock.mmss(7, 0);
    /** PROVISIONAL: minimum usable time at work to justify walking there (0:30 = 600t). */
    public static final int MIN_WORK_TICKS = ScheduleClock.mmss(0, 30);
    /** PROVISIONAL: minimum usable time for socializing (0:15 = 300t). */
    public static final int MIN_SOCIAL_TICKS = ScheduleClock.mmss(0, 15);
    /** PROVISIONAL: minimum usable time for a hobby trip (0:30 = 600t). */
    public static final int MIN_HOBBY_TICKS = ScheduleClock.mmss(0, 30);
    /** PROVISIONAL: minimum cap for a low-fulfilment preemptive social/hobby break (0.5 MC hr = 0:30 = 600t). */
    public static final int PREEMPT_MIN_TICKS = ScheduleClock.mmss(0, 30);
    /** PROVISIONAL: maximum cap for a low-fulfilment preemptive social/hobby break (2.0 MC hr = 2:00 = 2,400t). */
    public static final int PREEMPT_MAX_TICKS = ScheduleClock.mmss(2, 0);
    /** PROVISIONAL: ticks of daily social quota per unit of {@code socializingTargetInteractions} (0:06 = 120t). */
    public static final int SOCIAL_TICKS_PER_TARGET_UNIT = ScheduleClock.mmss(0, 6);
    /** PROVISIONAL: minimum buffer between a shifted meal's end and the following anchor's start (0:15 = 300t). */
    public static final int SHIFTED_MEAL_BUFFER_TICKS = ScheduleClock.mmss(0, 15);

    public enum AnchorKind {
        CONTINUE_SLEEP("continue_sleep"),
        LUNCH("lunch"),
        DINNER("dinner"),
        TONIGHT_SLEEP("tonight_sleep");

        private final String id;

        AnchorKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        @Nullable
        public static AnchorKind byId(@Nullable String id) {
            if (id == null) {
                return null;
            }
            for (AnchorKind k : values()) {
                if (k.id.equalsIgnoreCase(id) || k.name().equalsIgnoreCase(id)) {
                    return k;
                }
            }
            return null;
        }
    }

    public enum SlotKind {
        SLEEP("sleep"),
        MEAL("meal"),
        WORK("work"),
        SOCIALIZE("socialize"),
        HOBBY("hobby"),
        RELAX("relax");

        private final String id;

        SlotKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** Hard anchor entry in today's daily skeleton. */
    public record Anchor(
            AnchorKind kind,
            LocationKey location,
            int startTick,
            int durationTicks,
            int endTick,
            boolean shiftable,
            boolean completed
    ) {
        public Anchor {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(location, "location");
            startTick = Math.clamp(startTick, 0, ScheduleClock.DAY_TICKS_INT);
            durationTicks = Math.max(0, durationTicks);
            endTick = Math.clamp(endTick, startTick, ScheduleClock.DAY_TICKS_INT);
        }

        public Anchor withCompleted(boolean done) {
            return new Anchor(kind, location, startTick, durationTicks, endTick, shiftable, done);
        }

        /** Shifts a late-arriving shiftable anchor forward without exceeding {@code maxEndTick}. */
        public Anchor shiftedTo(int arrivalTick, int maxEndTick) {
            if (!shiftable || completed) {
                return this;
            }
            int newStart = Math.clamp(arrivalTick, startTick, ScheduleClock.DAY_TICKS_INT);
            int clampedMax = Math.clamp(maxEndTick, 0, ScheduleClock.DAY_TICKS_INT);
            if (newStart >= clampedMax) {
                return new Anchor(kind, location, newStart, durationTicks, newStart, true, true);
            }
            int newEnd = Math.min(newStart + durationTicks, clampedMax);
            return new Anchor(kind, location, newStart, durationTicks, newEnd, true, false);
        }
    }

    /** Resolved Level-2 Slot chosen by the scheduler for the current period or gap. */
    public record PlannedSlot(
            SlotKind kind,
            LocationKey location,
            @Nullable Activity hobby,
            int anchorIndex,
            int departAtTick,
            int preemptiveCapTicks,
            boolean quotaDriven,
            boolean preemptiveBreak
    ) {
        public boolean isAnchor() {
            return anchorIndex >= 0;
        }
    }

    /** Full scheduler decision returned at a decision point. */
    public record Decision(
            LocationKey destination,
            PlannedSlot slot,
            boolean mustTravelFirst,
            String reason
    ) {
    }

    /** Pure travel-time estimator callback supplied by the caller (usually backed by {@link RouteMemory}). */
    @FunctionalInterface
    public interface TravelEstimator {
        int estimateTicks(@Nullable LocationKey from, LocationKey to);
    }

    /** Immutable inputs for building today's skeleton at midnight / day rollover. */
    public record SkeletonInput(
            UUID civilianId,
            long scheduleDay,
            int nowTick,
            int sleepNeedTicks,
            int sleptSoFarTicks,
            boolean alreadyRestedTonight,
            CivilianTraits traits,
            CivilianDerivedStats stats,
            double fulfilment,
            double grievance,
            double grievanceThreshold,
            double socializingMeter,
            boolean hasWork,
            int employerWorkAskTicks,
            CrowdMemory crowdMemory
    ) {
    }

    /** Immutable inputs for choosing the next Slot at a live decision point. */
    public record PlanContext(
            UUID civilianId,
            long scheduleDay,
            int nowTick,
            @Nullable LocationKey currentLocation,
            boolean hasWork,
            boolean workIsOutdoor,
            boolean someoneNearbyToSocialize,
            boolean currentLocationComfortable,
            List<Activity> likedHobbies,
            double fulfilment,
            double grievance,
            double grievanceThreshold,
            CivilianTraits traits,
            TravelEstimator travel,
            CrowdMemory crowdMemory,
            Predicate<LocationKey> locationAvailable
    ) {
    }

    private long scheduleDay = NO_DAY;
    private final List<Anchor> anchors = new ArrayList<>();
    private int workQuotaTicks;
    private int workDoneTicks;
    private int socialQuotaTicks;
    private int socialDoneTicks;
    private boolean preemptiveBreakUsedThisGap;
    private int gapRollCounter;
    private final EnumMap<Activity, Long> lastHobbyDay = new EnumMap<>(Activity.class);

    public long scheduleDay() {
        return scheduleDay;
    }

    public boolean needsRebuild(long currentScheduleDay) {
        return scheduleDay != currentScheduleDay || anchors.isEmpty();
    }

    public List<Anchor> anchors() {
        return Collections.unmodifiableList(anchors);
    }

    public int workQuotaTicks() {
        return workQuotaTicks;
    }

    public int workDoneTicks() {
        return workDoneTicks;
    }

    public int remainingWorkTicks() {
        return Math.max(0, workQuotaTicks - workDoneTicks);
    }

    public int socialQuotaTicks() {
        return socialQuotaTicks;
    }

    public int socialDoneTicks() {
        return socialDoneTicks;
    }

    public int remainingSocialTicks() {
        return Math.max(0, socialQuotaTicks - socialDoneTicks);
    }

    public boolean preemptiveBreakUsedThisGap() {
        return preemptiveBreakUsedThisGap;
    }

    public Map<Activity, Long> lastHobbyDays() {
        return Collections.unmodifiableMap(lastHobbyDay);
    }

    public void addWorkDoneTicks(int ticks) {
        if (ticks > 0) {
            workDoneTicks += ticks;
        }
    }

    public void addSocialDoneTicks(int ticks) {
        if (ticks > 0) {
            socialDoneTicks += ticks;
        }
    }

    public void recordHobbyPerformed(Activity activity, long currentScheduleDay) {
        if (activity != null) {
            Long prev = lastHobbyDay.get(activity);
            if (prev == null || currentScheduleDay >= prev) {
                lastHobbyDay.put(activity, currentScheduleDay);
            }
        }
    }

    /** Marks an anchor complete and resets the per-gap preemptive break flag so the next gap starts fresh. */
    public void completeAnchor(int anchorIndex) {
        if (anchorIndex >= 0 && anchorIndex < anchors.size()) {
            anchors.set(anchorIndex, anchors.get(anchorIndex).withCompleted(true));
            preemptiveBreakUsedThisGap = false;
        }
    }

    /**
     * Handles arriving at a scheduled anchor at {@code arrivalTick}. If the anchor is shiftable (Lunch/Dinner) and the
     * civilian arrived late, shifts the meal window forward while preventing collision with the following anchor.
     */
    public Anchor onArriveAtAnchor(int anchorIndex, int arrivalTick, int travelToFollowingTicks) {
        if (anchorIndex < 0 || anchorIndex >= anchors.size()) {
            throw new IndexOutOfBoundsException("anchorIndex " + anchorIndex);
        }
        Anchor anchor = anchors.get(anchorIndex);
        if (anchor.shiftable() && !anchor.completed() && arrivalTick > anchor.startTick()) {
            int maxEnd = ScheduleClock.DAY_TICKS_INT;
            for (int i = anchorIndex + 1; i < anchors.size(); i++) {
                Anchor following = anchors.get(i);
                if (!following.completed()) {
                    maxEnd = Math.max(0, following.startTick() - Math.max(0, travelToFollowingTicks) - SHIFTED_MEAL_BUFFER_TICKS);
                    break;
                }
            }
            Anchor shifted = anchor.shiftedTo(arrivalTick, maxEnd);
            anchors.set(anchorIndex, shifted);
            return shifted;
        }
        return anchor;
    }

    /**
     * Builds (or rebuilds) today's Anchor Skeleton and daily work/social quotas. Designed to run lazily whenever
     * {@link #needsRebuild(long)} becomes true (midnight, night skip, unloaded days, or mid-day recruitment).
     */
    public void buildDailySkeleton(SkeletonInput in) {
        Objects.requireNonNull(in, "in");
        this.scheduleDay = in.scheduleDay();
        this.anchors.clear();
        this.workDoneTicks = 0;
        this.socialDoneTicks = 0;
        this.preemptiveBreakUsedThisGap = false;
        this.gapRollCounter = 0;

        int now = Math.clamp(in.nowTick(), 0, ScheduleClock.DAY_TICKS_INT - 1);
        int sleepNeed = Math.clamp(in.sleepNeedTicks(), 2_400, 10_800);
        int bedtime = ScheduleClock.tonightBedtimeTick(sleepNeed);

        // 1. Continue Sleep (midnight -> remaining sleep, capped at 04:33 / vanilla 23460)
        int wakeEnd = 0;
        if (!in.alreadyRestedTonight() && now < ScheduleClock.NIGHT_END_TICK) {
            int remaining = Math.max(0, sleepNeed - Math.max(0, in.sleptSoFarTicks()));
            int end = ScheduleClock.continueSleepEndTick(remaining);
            if (end > now) {
                anchors.add(new Anchor(AnchorKind.CONTINUE_SLEEP, LocationKey.HOME, 0, end, end, false, false));
                wakeEnd = end;
            }
        }

        // 2 & 3. Variable Lunch & Dinner (personal offset + basal exhaustion shift + Extraversion crowd pull + jitter)
        int lunchStart = computeMealStartTick(in.civilianId(), in.scheduleDay(), BASE_LUNCH_TICK,
                in.crowdMemory() != null ? in.crowdMemory().observedLunchTick() : CrowdMemory.UNOBSERVED,
                in.traits(), in.stats(), 0x11A4_BEEFL);
        int lunchMin = Math.max(wakeEnd + ScheduleClock.mmss(0, 30), ScheduleClock.mmss(6, 0));
        int lunchMax = ScheduleClock.mmss(11, 0);
        lunchStart = Math.clamp(lunchStart, lunchMin, lunchMax);
        boolean lunchPast = lunchStart + MEAL_DURATION_TICKS <= now;
        anchors.add(new Anchor(AnchorKind.LUNCH, LocationKey.FOOD_SITE,
                lunchStart, MEAL_DURATION_TICKS, lunchStart + MEAL_DURATION_TICKS, true, lunchPast));

        int dinnerStart = computeMealStartTick(in.civilianId(), in.scheduleDay(), BASE_DINNER_TICK,
                in.crowdMemory() != null ? in.crowdMemory().observedDinnerTick() : CrowdMemory.UNOBSERVED,
                in.traits(), in.stats(), 0x22B5_CAFEL);
        int dinnerMin = Math.max(lunchStart + ScheduleClock.mmss(2, 0), ScheduleClock.mmss(12, 0));
        int dinnerMax = Math.max(dinnerMin, bedtime - ScheduleClock.mmss(0, 45));
        dinnerStart = Math.clamp(dinnerStart, dinnerMin, dinnerMax);
        boolean dinnerPast = dinnerStart + MEAL_DURATION_TICKS <= now;
        anchors.add(new Anchor(AnchorKind.DINNER, LocationKey.FOOD_SITE,
                dinnerStart, MEAL_DURATION_TICKS, dinnerStart + MEAL_DURATION_TICKS, true, dinnerPast));

        // 4. Tonight's Sleep (exact match with CivilianSleep.bedtime -> 20:00)
        anchors.add(new Anchor(AnchorKind.TONIGHT_SLEEP, LocationKey.HOME,
                bedtime, ScheduleClock.DAY_TICKS_INT - bedtime, ScheduleClock.DAY_TICKS_INT, false, false));

        // Daily quotas
        this.workQuotaTicks = computeWorkQuotaTicks(in, now, wakeEnd, bedtime);
        this.socialQuotaTicks = computeSocialQuotaTicks(in.stats(), in.socializingMeter());
    }

    /** Updates today's work quota immediately (e.g. when a player/clerk assigns or clears a job mid-day). */
    public void updateWorkQuota(SkeletonInput in) {
        int now = Math.clamp(in.nowTick(), 0, ScheduleClock.DAY_TICKS_INT - 1);
        int bedtime = ScheduleClock.tonightBedtimeTick(Math.clamp(in.sleepNeedTicks(), 2_400, 10_800));
        int wakeEnd = 0;
        for (Anchor a : anchors) {
            if (a.kind() == AnchorKind.CONTINUE_SLEEP && !a.completed()) {
                wakeEnd = a.endTick();
            }
        }
        this.workQuotaTicks = computeWorkQuotaTicks(in, now, wakeEnd, bedtime);
    }

    /**
     * Live Gap-Filler: runs ONLY at decision points (never per tick) to pick the next Slot and whether travel is needed.
     */
    public Decision decideNextSlot(PlanContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        int now = Math.clamp(ctx.nowTick(), 0, ScheduleClock.DAY_TICKS_INT - 1);

        int nextIdx = firstUncompletedAnchorIndex();
        if (nextIdx < 0) {
            LocationKey relaxLoc = pickRelaxLocation(ctx, null);
            return new Decision(relaxLoc,
                    new PlannedSlot(SlotKind.RELAX, relaxLoc, null, -1, ScheduleClock.DAY_TICKS_INT, 0, false, false),
                    ctx.currentLocation() != relaxLoc, "no_anchors_left");
        }

        Anchor next = anchors.get(nextIdx);
        SlotKind nextSlotKind = anchorSlotKind(next.kind());

        // 1. Anchor is due right now (or we are running late for it)
        if (now >= next.startTick()) {
            boolean travelFirst = ctx.currentLocation() != next.location();
            PlannedSlot slot = new PlannedSlot(nextSlotKind, next.location(), null, nextIdx, next.endTick(), 0, false, false);
            return new Decision(next.location(), slot, travelFirst,
                    travelFirst ? "late_for_" + next.kind().id() : "anchor_" + next.kind().id());
        }

        // 2. Departure deadline: time remaining <= travel time to next anchor
        int timeUntilNext = next.startTick() - now;
        int travelToNext = Math.max(0, ctx.travel().estimateTicks(ctx.currentLocation(), next.location()));
        if (travelToNext >= timeUntilNext) {
            PlannedSlot slot = new PlannedSlot(nextSlotKind, next.location(), null, nextIdx, next.endTick(), 0, false, false);
            return new Decision(next.location(), slot, ctx.currentLocation() != next.location(),
                    "depart_for_" + next.kind().id());
        }

        // 3. Free gap: evaluate candidates with learned travel times, MIN_*_TICKS, and outdoor window
        boolean canWork = ctx.hasWork()
                && workDoneTicks < workQuotaTicks
                && ctx.locationAvailable().test(LocationKey.WORK)
                && isAllowedAtTime(LocationKey.WORK, ctx.workIsOutdoor(), now)
                && usableTicksAt(ctx, LocationKey.WORK, next, now) >= MIN_WORK_TICKS;

        // 3a. Probabilistic bounded low-fulfilment pre-work break (0.5..2.0 MC hours; Bright D6)
        if (canWork && !preemptiveBreakUsedThisGap) {
            double discontent = computeDiscontent(ctx.fulfilment(), ctx.grievance(), ctx.grievanceThreshold());
            if (discontent > 0.0 && rollPreemptiveBreak(ctx, next, discontent)) {
                int capTicks = preemptiveBreakCapTicks(discontent);
                preemptiveBreakUsedThisGap = true;
                Decision preempt = tryPickSocial(ctx, next, now, capTicks, true);
                if (preempt != null) {
                    return preempt;
                }
                Decision hobbyPreempt = tryPickHobby(ctx, next, now, capTicks, true);
                if (hobbyPreempt != null) {
                    return hobbyPreempt;
                }
            }
        }

        // 3b. Work
        if (canWork) {
            int departAt = departTickFrom(ctx, LocationKey.WORK, next);
            PlannedSlot slot = new PlannedSlot(SlotKind.WORK, LocationKey.WORK, null, -1, departAt, 0, true, false);
            return new Decision(LocationKey.WORK, slot, ctx.currentLocation() != LocationKey.WORK, "work_quota");
        }

        // 3c. Socialize (if social quota remaining)
        if (socialDoneTicks < socialQuotaTicks) {
            Decision social = tryPickSocial(ctx, next, now, 0, false);
            if (social != null) {
                return social;
            }
        }

        // 3d. Liked hobbies (ordered by longest time since last performed)
        Decision hobby = tryPickHobby(ctx, next, now, 0, false);
        if (hobby != null) {
            return hobby;
        }

        // 3e. Fallback: Relax (at comfortable spot or head early to next anchor)
        LocationKey relaxLoc = pickRelaxLocation(ctx, next);
        int departAt = departTickFrom(ctx, relaxLoc, next);
        PlannedSlot slot = new PlannedSlot(SlotKind.RELAX, relaxLoc, null, -1, departAt, 0, false, false);
        return new Decision(relaxLoc, slot, ctx.currentLocation() != relaxLoc, "relax_fallback");
    }

    @Nullable
    private Decision tryPickSocial(PlanContext ctx, Anchor next, int now, int capTicks, boolean preemptive) {
        LocationKey socialLoc = pickBestSocialLocation(ctx, next, now);
        if (socialLoc == null) {
            return null;
        }
        int departAt = departTickFrom(ctx, socialLoc, next);
        PlannedSlot slot = new PlannedSlot(SlotKind.SOCIALIZE, socialLoc, null, -1, departAt, capTicks, true, preemptive);
        return new Decision(socialLoc, slot, ctx.currentLocation() != socialLoc,
                preemptive ? "preemptive_socialize" : "socialize_quota");
    }

    @Nullable
    private Decision tryPickHobby(PlanContext ctx, Anchor next, int now, int capTicks, boolean preemptive) {
        List<Activity> hobbies = ctx.likedHobbies();
        if (hobbies == null || hobbies.isEmpty()) {
            return null;
        }
        List<Activity> sorted = new ArrayList<>(hobbies);
        sorted.sort(Comparator.comparingLong(a -> lastHobbyDay.getOrDefault(a, Long.MIN_VALUE)));
        for (Activity hobby : sorted) {
            LocationKey loc = LocationKey.forHobby(hobby);
            if (!ctx.locationAvailable().test(loc)) {
                continue;
            }
            if (!isAllowedAtTime(loc, false, now)) {
                continue;
            }
            if (usableTicksAt(ctx, loc, next, now) >= MIN_HOBBY_TICKS) {
                int departAt = departTickFrom(ctx, loc, next);
                PlannedSlot slot = new PlannedSlot(SlotKind.HOBBY, loc, hobby, -1, departAt, capTicks, false, preemptive);
                return new Decision(loc, slot, ctx.currentLocation() != loc,
                        (preemptive ? "preemptive_hobby_" : "hobby_") + hobby.id());
            }
        }
        return null;
    }

    @Nullable
    private LocationKey pickBestSocialLocation(PlanContext ctx, Anchor next, int now) {
        // 1. Someone is already nearby right here
        if (ctx.someoneNearbyToSocialize() && ctx.currentLocation() != null
                && usableTicksAt(ctx, ctx.currentLocation(), next, now) >= MIN_SOCIAL_TICKS) {
            return ctx.currentLocation();
        }
        // 2. Likely someone at the next anchor (seen in last 3 days)
        if (ctx.crowdMemory() != null && ctx.crowdMemory().likelyOccupied(next.location(), ctx.scheduleDay())
                && ctx.locationAvailable().test(next.location())
                && usableTicksAt(ctx, next.location(), next, now) >= MIN_SOCIAL_TICKS) {
            return next.location();
        }
        // 3. Known social hub (Pub / Bonfire, seen in last 3 days)
        if (ctx.crowdMemory() != null && ctx.crowdMemory().likelyOccupied(LocationKey.SOCIAL_HUB, ctx.scheduleDay())
                && ctx.locationAvailable().test(LocationKey.SOCIAL_HUB)
                && usableTicksAt(ctx, LocationKey.SOCIAL_HUB, next, now) >= MIN_SOCIAL_TICKS) {
            return LocationKey.SOCIAL_HUB;
        }
        return null;
    }

    private LocationKey pickRelaxLocation(PlanContext ctx, @Nullable Anchor next) {
        if (ctx.currentLocationComfortable() && ctx.currentLocation() != null) {
            return ctx.currentLocation();
        }
        if (next != null) {
            if (ctx.locationAvailable().test(LocationKey.HOME)
                    && usableTicksAt(ctx, LocationKey.HOME, next, ctx.nowTick()) >= MIN_SOCIAL_TICKS) {
                return LocationKey.HOME;
            }
            return next.location();
        }
        return ctx.locationAvailable().test(LocationKey.HOME) ? LocationKey.HOME : LocationKey.FOOD_SITE;
    }

    private static int usableTicksAt(PlanContext ctx, LocationKey dest, Anchor next, int now) {
        int toDest = Math.max(0, ctx.travel().estimateTicks(ctx.currentLocation(), dest));
        int destToNext = dest == next.location() ? 0 : Math.max(0, ctx.travel().estimateTicks(dest, next.location()));
        return next.startTick() - now - toDest - destToNext;
    }

    private static int departTickFrom(PlanContext ctx, LocationKey dest, Anchor next) {
        int destToNext = dest == next.location() ? 0 : Math.max(0, ctx.travel().estimateTicks(dest, next.location()));
        return Math.max(0, next.startTick() - destToNext);
    }

    private static boolean isAllowedAtTime(LocationKey loc, boolean workIsOutdoor, int nowTick) {
        boolean outdoor = loc == LocationKey.WORK ? workIsOutdoor : loc.isAlwaysOutdoor();
        return !outdoor || ScheduleClock.isOutdoorTime(nowTick);
    }

    private int firstUncompletedAnchorIndex() {
        for (int i = 0; i < anchors.size(); i++) {
            if (!anchors.get(i).completed()) {
                return i;
            }
        }
        return -1;
    }

    private static SlotKind anchorSlotKind(AnchorKind kind) {
        return switch (kind) {
            case CONTINUE_SLEEP, TONIGHT_SLEEP -> SlotKind.SLEEP;
            case LUNCH, DINNER -> SlotKind.MEAL;
        };
    }

    /**
     * Computes variable mealtime from deterministic personal offset (biased earlier by basal exhaustion), Extraversion
     * crowd pull toward observed hub peak (or push away for Solitary), and small daily jitter.
     */
    public static int computeMealStartTick(UUID civilianId, long scheduleDay, int baseTick, int observedCrowdTick,
                                           CivilianTraits traits, CivilianDerivedStats stats, long salt) {
        long personalSeed = civilianId.getMostSignificantBits() ^ (civilianId.getLeastSignificantBits() * 31L) ^ salt;
        Random personalRng = new Random(personalSeed);
        double personalUnit = personalRng.nextDouble() * 2.0 - 1.0; // [-1, +1]
        double exhaustShift = -(stats.basalExhaustionMultiplier() - 1.0) * 300.0;
        double personalTick = baseTick + personalUnit * MEAL_PERSONAL_OFFSET_MAX_TICKS + exhaustShift;

        int crowdTarget = observedCrowdTick >= 0 ? observedCrowdTick : baseTick;
        double e = traits.norm(BaseTrait.EXTRAVERSION);
        double adjusted;
        if (e >= 0.0) {
            double pull = 0.70 * e;
            adjusted = personalTick + (crowdTarget - personalTick) * pull;
        } else {
            // Solitary citizens drift slightly away from the busy peak
            double awayDir = personalTick >= crowdTarget ? 1.0 : -1.0;
            adjusted = personalTick + awayDir * (-e) * 0.30 * MEAL_PERSONAL_OFFSET_MAX_TICKS;
        }

        long daySeed = personalSeed ^ (scheduleDay * 0x9E37_79B9_7F4A_7C15L);
        Random dayRng = new Random(daySeed);
        double jitter = (dayRng.nextDouble() * 2.0 - 1.0) * MEAL_DAILY_JITTER_TICKS;

        int raw = (int) Math.round(adjusted + jitter);
        return Math.clamp(raw, baseTick - MEAL_MAX_SHIFT_TICKS, baseTick + MEAL_MAX_SHIFT_TICKS);
    }

    /** Computes discontent level in {@code [0, 1]} from Fulfilment shortfall below threshold and Grievance. */
    public static double computeDiscontent(double fulfilment, double grievance, double threshold) {
        double safeThreshold = Double.isFinite(threshold) && threshold > 0.0 ? threshold : FulfilmentModel.DEFAULT_GRIEVANCE_THRESHOLD;
        double safeFulfil = Double.isFinite(fulfilment) ? Math.max(0.0, fulfilment) : safeThreshold;
        double safeGriev = Double.isFinite(grievance) ? FulfilmentModel.clamp01(grievance) : 0.0;
        double shortfall = safeFulfil < safeThreshold ? (safeThreshold - safeFulfil) / safeThreshold : 0.0;
        return FulfilmentModel.clamp01(Math.max(shortfall, safeGriev));
    }

    /** Trait predisposition in {@code [0.05, 0.95]} for putting a bounded personal break ahead of work when discontent. */
    public static double preemptionPredisposition(CivilianTraits traits) {
        double c = traits.norm(BaseTrait.CONSCIENTIOUSNESS);
        double a = traits.norm(BaseTrait.AGREEABLENESS);
        double n = traits.norm(BaseTrait.NEUROTICISM);
        double o = traits.norm(BaseTrait.OPENNESS);
        return Math.clamp(0.35 - 0.30 * c - 0.25 * a + 0.20 * n + 0.15 * o, 0.05, 0.95);
    }

    /** Bounded pre-work hobby/social break duration ({@code 0.5..2.0} MC hours = {@code 600..2,400} ticks). */
    public static int preemptiveBreakCapTicks(double discontent) {
        double d = FulfilmentModel.clamp01(discontent);
        return (int) Math.round(PREEMPT_MIN_TICKS + (PREEMPT_MAX_TICKS - PREEMPT_MIN_TICKS) * d);
    }

    private boolean rollPreemptiveBreak(PlanContext ctx, Anchor next, double discontent) {
        double prob = FulfilmentModel.clamp01(discontent * preemptionPredisposition(ctx.traits()));
        if (prob <= 0.0) {
            return false;
        }
        long seed = ctx.civilianId().getMostSignificantBits()
                ^ (ctx.civilianId().getLeastSignificantBits() * 31L)
                ^ (ctx.scheduleDay() * 0x517C_C1B7_2722_0A95L)
                ^ ((long) next.kind().ordinal() << 16)
                ^ (++gapRollCounter * 0x9E37_79B9L);
        return new Random(seed).nextDouble() < prob;
    }

    private static int computeWorkQuotaTicks(SkeletonInput in, int nowTick, int wakeEnd, int bedtime) {
        if (!in.hasWork() || in.employerWorkAskTicks() <= 0) {
            return 0;
        }
        double staminaFactor = Math.clamp(0.80 + 0.40 * ((in.stats().sustainedLaborStamina() - 0.55) / 1.0), 0.80, 1.20);
        double socialRatio = Math.clamp(
                0.60 + 0.20 * in.traits().norm(BaseTrait.AGREEABLENESS) + 0.15 * in.traits().norm(BaseTrait.CONSCIENTIOUSNESS),
                0.25, 0.95);
        double discontent = computeDiscontent(in.fulfilment(), in.grievance(), in.grievanceThreshold());
        double willingness = Math.clamp(
                (0.85 + 0.30 * (socialRatio - 0.60)) * (1.0 - 0.45 * discontent * (1.0 - 0.5 * socialRatio)),
                0.45, 1.15);

        int fullWaking = Math.max(1, bedtime - wakeEnd);
        int remainingWaking = Math.max(0, bedtime - Math.max(nowTick, wakeEnd));
        double remainingFraction = Math.clamp((double) remainingWaking / (double) fullWaking, 0.0, 1.0);

        int quota = (int) Math.round(in.employerWorkAskTicks() * staminaFactor * willingness * remainingFraction);
        return Math.clamp(quota, 0, remainingWaking);
    }

    private static int computeSocialQuotaTicks(CivilianDerivedStats stats, double socializingMeter) {
        double targetUnits = stats.socializingTargetInteractions();
        double shortfallScale = 0.40 + 0.60 * (1.0 - FulfilmentModel.clamp01(socializingMeter));
        return Math.max(MIN_SOCIAL_TICKS, (int) Math.round(targetUnits * SOCIAL_TICKS_PER_TARGET_UNIT * shortfallScale));
    }

    public String summaryLine(int nowTick) {
        int nextIdx = firstUncompletedAnchorIndex();
        String nextStr = nextIdx >= 0
                ? anchors.get(nextIdx).kind().id() + "@" + ScheduleClock.format(anchors.get(nextIdx).startTick())
                : "none";
        return String.format(Locale.ROOT,
                "Day %d (%s) | Work %s/%s | Social %s/%s | Next %s",
                scheduleDay, ScheduleClock.format(nowTick),
                ScheduleClock.format(workDoneTicks), ScheduleClock.format(workQuotaTicks),
                ScheduleClock.format(socialDoneTicks), ScheduleClock.format(socialQuotaTicks),
                nextStr);
    }

    public Saved save() {
        return new Saved(
                scheduleDay,
                List.copyOf(anchors),
                workQuotaTicks,
                workDoneTicks,
                socialQuotaTicks,
                socialDoneTicks,
                preemptiveBreakUsedThisGap,
                gapRollCounter,
                Collections.unmodifiableMap(new EnumMap<>(lastHobbyDay))
        );
    }

    public static CivilianSchedule restore(Saved saved) {
        CivilianSchedule s = new CivilianSchedule();
        if (saved != null) {
            s.scheduleDay = saved.scheduleDay();
            s.anchors.addAll(saved.anchors());
            s.workQuotaTicks = Math.max(0, saved.workQuotaTicks());
            s.workDoneTicks = Math.max(0, saved.workDoneTicks());
            s.socialQuotaTicks = Math.max(0, saved.socialQuotaTicks());
            s.socialDoneTicks = Math.max(0, saved.socialDoneTicks());
            s.preemptiveBreakUsedThisGap = saved.preemptiveBreakUsedThisGap();
            s.gapRollCounter = Math.max(0, saved.gapRollCounter());
            for (Map.Entry<Activity, Long> e : saved.lastHobbyDay().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    s.lastHobbyDay.put(e.getKey(), e.getValue());
                }
            }
        }
        return s;
    }

    public CivilianSchedule copy() {
        return restore(save());
    }

    public record Saved(
            long scheduleDay,
            List<Anchor> anchors,
            int workQuotaTicks,
            int workDoneTicks,
            int socialQuotaTicks,
            int socialDoneTicks,
            boolean preemptiveBreakUsedThisGap,
            int gapRollCounter,
            Map<Activity, Long> lastHobbyDay
    ) {
        public static final Saved EMPTY = new Saved(NO_DAY, List.of(), 0, 0, 0, 0, false, 0, Map.of());
    }
}
