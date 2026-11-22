// Written by Gemini.
package dev.brights0ng.empire.rules;

/**
 * Empire-wide 3-day rolling counter of hostile hits against citizens ({@code docs/empire/civilian-fulfilment.md} §2,
 * Safety meter). Advances only on active ticks while at least one citizen of the empire is loaded, so frozen empires
 * do not lose hit history while unloaded.
 */
public final class EmpireSafetyTracker {

    public static final int WINDOW_DAYS = 3;

    private final int[] hitsByDay = new int[WINDOW_DAYS];
    private long activeTicks;
    /** Transient deduplication guard so multiple loaded citizens advance activeTicks at most once per game tick. */
    private long lastCountedGameTime = Long.MIN_VALUE;

    /** Advances the empire safety active-tick clock at most once per {@code gameTime}. Returns true if a day rolled. */
    public boolean countActiveTickIfNew(long gameTime) {
        if (gameTime == lastCountedGameTime) {
            return false;
        }
        lastCountedGameTime = gameTime;
        return countActiveTick();
    }

    /** Advances the active-tick clock by one tick and rolls the 3-day hit buckets every {@link CivilianFood#DAY_LENGTH}. */
    public boolean countActiveTick() {
        activeTicks++;
        if (activeTicks % CivilianFood.DAY_LENGTH == 0L) {
            hitsByDay[2] = hitsByDay[1];
            hitsByDay[1] = hitsByDay[0];
            hitsByDay[0] = 0;
            return true;
        }
        return false;
    }

    /** Records one hit dealt to a citizen of this empire by a monster or outsider player. */
    public void recordHit() {
        hitsByDay[0]++;
    }

    public int hitsToday() {
        return hitsByDay[0];
    }

    public int hitsYesterday() {
        return hitsByDay[1];
    }

    public int hitsTwoDaysAgo() {
        return hitsByDay[2];
    }

    public int hitsLast3Days() {
        return hitsByDay[0] + hitsByDay[1] + hitsByDay[2];
    }

    public long activeTicks() {
        return activeTicks;
    }

    public Saved save() {
        return new Saved(hitsByDay[0], hitsByDay[1], hitsByDay[2], activeTicks);
    }

    public static EmpireSafetyTracker restore(Saved saved) {
        EmpireSafetyTracker tracker = new EmpireSafetyTracker();
        tracker.hitsByDay[0] = Math.max(0, saved.day0());
        tracker.hitsByDay[1] = Math.max(0, saved.day1());
        tracker.hitsByDay[2] = Math.max(0, saved.day2());
        tracker.activeTicks = Math.max(0L, saved.activeTicks());
        return tracker;
    }

    public record Saved(int day0, int day1, int day2, long activeTicks) {
    }
}
