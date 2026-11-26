// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.CivilianPreferences.Activity;
import dev.brights0ng.empire.rules.CivilianSchedule;
import dev.brights0ng.empire.rules.CivilianSchedule.Anchor;
import dev.brights0ng.empire.rules.CivilianSchedule.AnchorKind;
import dev.brights0ng.empire.rules.CrowdMemory;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.Occupation;
import dev.brights0ng.empire.rules.RouteMemory;
import dev.brights0ng.empire.rules.ScheduleClock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Shared tolerant NBT codec for a citizen's persistent schedule state, route memory, 3-day crowd memory, and assigned
 * workplace ({@link WorkAssignment}), used by both {@link CivilianRecord} and
 * {@link dev.brights0ng.empire.civilian.CivilianEntity}.
 */
public final class CivilianScheduleNbt {

    private static final String KEY_SCHEDULE_DAY = "schedule_day";
    private static final String KEY_ANCHORS = "anchors";
    private static final String KEY_WORK_QUOTA = "work_quota";
    private static final String KEY_WORK_DONE = "work_done";
    private static final String KEY_SOCIAL_QUOTA = "social_quota";
    private static final String KEY_SOCIAL_DONE = "social_done";
    private static final String KEY_PREEMPT_USED = "preempt_used";
    private static final String KEY_GAP_ROLLS = "gap_rolls";
    private static final String KEY_LAST_HOBBY_DAY = "last_hobby_day";
    private static final String KEY_ROUTES = "routes";
    private static final String KEY_ROUTE_ESTIMATES = "estimates";
    private static final String KEY_ROUTE_ANCHORS = "route_anchors";
    private static final String KEY_CROWD = "crowd";
    private static final String KEY_CROWD_SEEN = "seen";
    private static final String KEY_LUNCH_PEAK = "lunch_peak";
    private static final String KEY_DINNER_PEAK = "dinner_peak";
    private static final String KEY_WORK = "work";

    /** Combined immutable snapshot of all persisted AI planning state for one civilian. */
    public record SavedBundle(
            CivilianSchedule.Saved schedule,
            RouteMemory.Saved routes,
            CrowdMemory.Saved crowd,
            @Nullable WorkAssignment work
    ) {
        public static final SavedBundle EMPTY = new SavedBundle(
                CivilianSchedule.Saved.EMPTY,
                RouteMemory.Saved.EMPTY,
                CrowdMemory.Saved.EMPTY,
                null
        );

        public SavedBundle {
            schedule = Objects.requireNonNull(schedule, "schedule");
            routes = Objects.requireNonNull(routes, "routes");
            crowd = Objects.requireNonNull(crowd, "crowd");
        }
    }

    public static CompoundTag save(SavedBundle bundle) {
        CompoundTag tag = new CompoundTag();
        CivilianSchedule.Saved s = bundle.schedule();
        tag.putLong(KEY_SCHEDULE_DAY, s.scheduleDay());

        ListTag anchorsTag = new ListTag();
        for (Anchor a : s.anchors()) {
            CompoundTag at = new CompoundTag();
            at.putString("kind", a.kind().id());
            at.putString("loc", a.location().id());
            at.putInt("start", a.startTick());
            at.putInt("dur", a.durationTicks());
            at.putInt("end", a.endTick());
            at.putBoolean("shift", a.shiftable());
            at.putBoolean("done", a.completed());
            anchorsTag.add(at);
        }
        tag.put(KEY_ANCHORS, anchorsTag);
        tag.putInt(KEY_WORK_QUOTA, s.workQuotaTicks());
        tag.putInt(KEY_WORK_DONE, s.workDoneTicks());
        tag.putInt(KEY_SOCIAL_QUOTA, s.socialQuotaTicks());
        tag.putInt(KEY_SOCIAL_DONE, s.socialDoneTicks());
        tag.putBoolean(KEY_PREEMPT_USED, s.preemptiveBreakUsedThisGap());
        tag.putInt(KEY_GAP_ROLLS, s.gapRollCounter());

        CompoundTag hobbiesTag = new CompoundTag();
        for (Map.Entry<Activity, Long> e : s.lastHobbyDay().entrySet()) {
            hobbiesTag.putLong(e.getKey().id(), e.getValue());
        }
        tag.put(KEY_LAST_HOBBY_DAY, hobbiesTag);

        // RouteMemory
        CompoundTag routesTag = new CompoundTag();
        ListTag estTag = new ListTag();
        for (Map.Entry<RouteMemory.Pair, Integer> e : bundle.routes().estimates().entrySet()) {
            CompoundTag et = new CompoundTag();
            et.putString("a", e.getKey().a().id());
            et.putString("b", e.getKey().b().id());
            et.putInt("ticks", e.getValue());
            estTag.add(et);
        }
        routesTag.put(KEY_ROUTE_ESTIMATES, estTag);
        CompoundTag routeAnchorsTag = new CompoundTag();
        for (Map.Entry<LocationKey, Long> e : bundle.routes().anchors().entrySet()) {
            routeAnchorsTag.putLong(e.getKey().id(), e.getValue());
        }
        routesTag.put(KEY_ROUTE_ANCHORS, routeAnchorsTag);
        tag.put(KEY_ROUTES, routesTag);

        // CrowdMemory
        CompoundTag crowdTag = new CompoundTag();
        CompoundTag seenTag = new CompoundTag();
        for (Map.Entry<LocationKey, Long> e : bundle.crowd().lastSeenDay().entrySet()) {
            seenTag.putLong(e.getKey().id(), e.getValue());
        }
        crowdTag.put(KEY_CROWD_SEEN, seenTag);
        crowdTag.putInt(KEY_LUNCH_PEAK, bundle.crowd().observedLunchTick());
        crowdTag.putInt(KEY_DINNER_PEAK, bundle.crowd().observedDinnerTick());
        tag.put(KEY_CROWD, crowdTag);

        // Optional WorkAssignment
        WorkAssignment work = bundle.work();
        if (work != null) {
            CompoundTag wt = new CompoundTag();
            if (work.occupation().isEmployed()) {
                wt.putString("occupation", work.occupation().id());
            }
            if (work.workplaceRegionId() != null) {
                wt.putUUID("region", work.workplaceRegionId());
            }
            if (work.anchor() != null) {
                wt.putLong("pos", work.anchor().asLong());
            }
            wt.putBoolean("indoor", work.indoor());
            wt.putInt("ask", work.dailyAskTicks());
            wt.putString("label", work.label());
            tag.put(KEY_WORK, wt);
        }

        return tag;
    }

    /** Loads a saved bundle with independent safe defaults for every missing or corrupt field. */
    public static SavedBundle load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return SavedBundle.EMPTY;
        }
        long day = tag.contains(KEY_SCHEDULE_DAY, Tag.TAG_ANY_NUMERIC)
                ? tag.getLong(KEY_SCHEDULE_DAY) : CivilianSchedule.NO_DAY;

        List<Anchor> anchors = new ArrayList<>();
        if (tag.contains(KEY_ANCHORS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_ANCHORS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag at = list.getCompound(i);
                AnchorKind kind = AnchorKind.byId(at.getString("kind"));
                LocationKey loc = LocationKey.byId(at.getString("loc"));
                if (kind == null || loc == null) {
                    continue;
                }
                int start = Math.clamp(at.getInt("start"), 0, ScheduleClock.DAY_TICKS_INT);
                int dur = Math.max(0, at.getInt("dur"));
                int end = Math.clamp(at.getInt("end"), start, ScheduleClock.DAY_TICKS_INT);
                boolean shift = at.getBoolean("shift");
                boolean done = at.getBoolean("done");
                anchors.add(new Anchor(kind, loc, start, dur, end, shift, done));
            }
        }
        int workQuota = Math.max(0, tag.getInt(KEY_WORK_QUOTA));
        int workDone = Math.max(0, tag.getInt(KEY_WORK_DONE));
        int socialQuota = Math.max(0, tag.getInt(KEY_SOCIAL_QUOTA));
        int socialDone = Math.max(0, tag.getInt(KEY_SOCIAL_DONE));
        boolean preemptUsed = tag.getBoolean(KEY_PREEMPT_USED);
        int gapRolls = Math.max(0, tag.getInt(KEY_GAP_ROLLS));

        EnumMap<Activity, Long> lastHobbyDay = new EnumMap<>(Activity.class);
        if (tag.contains(KEY_LAST_HOBBY_DAY, Tag.TAG_COMPOUND)) {
            CompoundTag ht = tag.getCompound(KEY_LAST_HOBBY_DAY);
            for (String key : ht.getAllKeys()) {
                Activity act = Activity.byId(key);
                if (act != null && ht.contains(key, Tag.TAG_ANY_NUMERIC)) {
                    lastHobbyDay.put(act, ht.getLong(key));
                }
            }
        }
        CivilianSchedule.Saved schedSaved = new CivilianSchedule.Saved(
                day, List.copyOf(anchors), workQuota, workDone, socialQuota, socialDone,
                preemptUsed, gapRolls, Collections.unmodifiableMap(lastHobbyDay));

        // RouteMemory
        Map<RouteMemory.Pair, Integer> estimates = new LinkedHashMap<>();
        EnumMap<LocationKey, Long> routeAnchors = new EnumMap<>(LocationKey.class);
        if (tag.contains(KEY_ROUTES, Tag.TAG_COMPOUND)) {
            CompoundTag rt = tag.getCompound(KEY_ROUTES);
            if (rt.contains(KEY_ROUTE_ESTIMATES, Tag.TAG_LIST)) {
                ListTag list = rt.getList(KEY_ROUTE_ESTIMATES, Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag et = list.getCompound(i);
                    LocationKey a = LocationKey.byId(et.getString("a"));
                    LocationKey b = LocationKey.byId(et.getString("b"));
                    int ticks = et.getInt("ticks");
                    if (a != null && b != null && a != b && ticks > 0) {
                        estimates.put(new RouteMemory.Pair(a, b), Math.max(RouteMemory.MIN_TRAVEL_TICKS, ticks));
                    }
                }
            }
            if (rt.contains(KEY_ROUTE_ANCHORS, Tag.TAG_COMPOUND)) {
                CompoundTag rat = rt.getCompound(KEY_ROUTE_ANCHORS);
                for (String key : rat.getAllKeys()) {
                    LocationKey loc = LocationKey.byId(key);
                    if (loc != null && rat.contains(key, Tag.TAG_ANY_NUMERIC)) {
                        routeAnchors.put(loc, rat.getLong(key));
                    }
                }
            }
        }
        RouteMemory.Saved routesSaved = new RouteMemory.Saved(
                Collections.unmodifiableMap(estimates),
                Collections.unmodifiableMap(routeAnchors));

        // CrowdMemory
        EnumMap<LocationKey, Long> seen = new EnumMap<>(LocationKey.class);
        int lunchPeak = CrowdMemory.UNOBSERVED;
        int dinnerPeak = CrowdMemory.UNOBSERVED;
        if (tag.contains(KEY_CROWD, Tag.TAG_COMPOUND)) {
            CompoundTag ct = tag.getCompound(KEY_CROWD);
            if (ct.contains(KEY_CROWD_SEEN, Tag.TAG_COMPOUND)) {
                CompoundTag st = ct.getCompound(KEY_CROWD_SEEN);
                for (String key : st.getAllKeys()) {
                    LocationKey loc = LocationKey.byId(key);
                    if (loc != null && st.contains(key, Tag.TAG_ANY_NUMERIC)) {
                        seen.put(loc, st.getLong(key));
                    }
                }
            }
            if (ct.contains(KEY_LUNCH_PEAK, Tag.TAG_ANY_NUMERIC)) {
                int v = ct.getInt(KEY_LUNCH_PEAK);
                if (v >= 0 && v < ScheduleClock.DAY_TICKS_INT) {
                    lunchPeak = v;
                }
            }
            if (ct.contains(KEY_DINNER_PEAK, Tag.TAG_ANY_NUMERIC)) {
                int v = ct.getInt(KEY_DINNER_PEAK);
                if (v >= 0 && v < ScheduleClock.DAY_TICKS_INT) {
                    dinnerPeak = v;
                }
            }
        }
        CrowdMemory.Saved crowdSaved = new CrowdMemory.Saved(
                Collections.unmodifiableMap(seen), lunchPeak, dinnerPeak);

        // Optional WorkAssignment
        WorkAssignment work = null;
        if (tag.contains(KEY_WORK, Tag.TAG_COMPOUND)) {
            CompoundTag wt = tag.getCompound(KEY_WORK);
            Occupation occ = wt.contains("occupation", Tag.TAG_STRING)
                    ? Occupation.byId(wt.getString("occupation"))
                    : null;
            UUID regionId = wt.hasUUID("region") ? wt.getUUID("region") : null;
            BlockPos pos = wt.contains("pos", Tag.TAG_ANY_NUMERIC) ? BlockPos.of(wt.getLong("pos")) : null;
            if (occ != null && occ.isEmployed() && regionId != null) {
                boolean indoor = wt.contains("indoor", Tag.TAG_ANY_NUMERIC) ? wt.getBoolean("indoor") : occ.indoor();
                int ask = wt.contains("ask", Tag.TAG_ANY_NUMERIC) ? wt.getInt("ask") : occ.defaultDailyAskTicks();
                String label = wt.contains("label", Tag.TAG_STRING) ? wt.getString("label") : occ.id();
                work = new WorkAssignment(occ, regionId, pos, indoor, ask, label);
            } else if (pos != null) {
                boolean indoor = wt.getBoolean("indoor");
                int ask = wt.contains("ask", Tag.TAG_ANY_NUMERIC)
                        ? wt.getInt("ask") : CivilianSchedule.DEFAULT_WORK_ASK_TICKS;
                String label = wt.getString("label");
                work = WorkAssignment.of(pos, indoor, ask, label);
            }
        }

        return new SavedBundle(schedSaved, routesSaved, crowdSaved, work);
    }

    private CivilianScheduleNbt() {
    }
}
