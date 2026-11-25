// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight 3-day per-location social sighting memory plus observed mealtime peaks at {@link LocationKey#FOOD_SITE}
 * (zero Minecraft imports).
 *
 * <p>Drives two scheduler decisions:
 * <ol>
 *   <li>Whether a location is likely occupied for socializing ("seen people there in the last 3 days").</li>
 *   <li>The Extraversion crowd-pull target for variable Lunch and Dinner anchor times.</li>
 * </ol>
 */
public final class CrowdMemory {

    /** Number of schedule days a sighting keeps a location marked as likely occupied. */
    public static final long MEMORY_DAYS = 3L;
    /** Sentinel meaning no mealtime crowd peak has been observed yet. */
    public static final int UNOBSERVED = -1;
    /** EMA smoothing factor for observed mealtime peaks at {@link LocationKey#FOOD_SITE}. */
    public static final double PEAK_EMA_ALPHA = 0.25;

    /** Window around Lunch (07:00..11:00) where food-site sightings refine the lunch crowd peak. */
    public static final int LUNCH_WINDOW_START = ScheduleClock.mmss(7, 0);
    public static final int LUNCH_WINDOW_END = ScheduleClock.mmss(11, 0);
    /** Window around Dinner (12:00..16:00) where food-site sightings refine the dinner crowd peak. */
    public static final int DINNER_WINDOW_START = ScheduleClock.mmss(12, 0);
    public static final int DINNER_WINDOW_END = ScheduleClock.mmss(16, 0);

    private final EnumMap<LocationKey, Long> lastSeenDay = new EnumMap<>(LocationKey.class);
    private int observedLunchTick = UNOBSERVED;
    private int observedDinnerTick = UNOBSERVED;

    /** Records seeing another person at {@code location} on {@code scheduleDay} at {@code tickOfDay}. */
    public void recordSighting(LocationKey location, long scheduleDay, int tickOfDay) {
        Objects.requireNonNull(location, "location");
        Long prev = lastSeenDay.get(location);
        if (prev == null || scheduleDay >= prev) {
            lastSeenDay.put(location, scheduleDay);
        }
        int t = Math.floorMod(tickOfDay, ScheduleClock.DAY_TICKS_INT);
        if (location == LocationKey.FOOD_SITE || location == LocationKey.SOCIAL_HUB) {
            if (t >= LUNCH_WINDOW_START && t <= LUNCH_WINDOW_END) {
                observedLunchTick = smoothTick(observedLunchTick, t);
            } else if (t >= DINNER_WINDOW_START && t <= DINNER_WINDOW_END) {
                observedDinnerTick = smoothTick(observedDinnerTick, t);
            }
        }
    }

    /** True if another person was seen at {@code location} within the last {@value #MEMORY_DAYS} days. */
    public boolean likelyOccupied(LocationKey location, long currentScheduleDay) {
        if (location == null) {
            return false;
        }
        Long seen = lastSeenDay.get(location);
        if (seen == null) {
            return false;
        }
        long age = currentScheduleDay - seen;
        return age >= 0L && age <= MEMORY_DAYS;
    }

    public int observedLunchTick() {
        return observedLunchTick;
    }

    public int observedDinnerTick() {
        return observedDinnerTick;
    }

    public Map<LocationKey, Long> lastSeenDays() {
        return Collections.unmodifiableMap(lastSeenDay);
    }

    public Saved save() {
        return new Saved(
                Collections.unmodifiableMap(new EnumMap<>(lastSeenDay)),
                observedLunchTick,
                observedDinnerTick
        );
    }

    public static CrowdMemory restore(Saved saved) {
        CrowdMemory mem = new CrowdMemory();
        if (saved != null) {
            for (Map.Entry<LocationKey, Long> e : saved.lastSeenDay().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    mem.lastSeenDay.put(e.getKey(), e.getValue());
                }
            }
            mem.observedLunchTick = validateTick(saved.observedLunchTick());
            mem.observedDinnerTick = validateTick(saved.observedDinnerTick());
        }
        return mem;
    }

    public CrowdMemory copy() {
        return restore(save());
    }

    private static int smoothTick(int current, int sample) {
        if (current < 0) {
            return sample;
        }
        return (int) Math.round(current * (1.0 - PEAK_EMA_ALPHA) + sample * PEAK_EMA_ALPHA);
    }

    private static int validateTick(int tick) {
        return tick >= 0 && tick < ScheduleClock.DAY_TICKS_INT ? tick : UNOBSERVED;
    }

    public record Saved(Map<LocationKey, Long> lastSeenDay, int observedLunchTick, int observedDinnerTick) {
        public static final Saved EMPTY = new Saved(Map.of(), UNOBSERVED, UNOBSERVED);
    }
}
