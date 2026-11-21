// Written by Gemini.
package dev.brights0ng.empire.rules;

/**
 * Pure civilian sleep state and rules (no Minecraft imports).
 *
 * <p>Sleep need scales with raw Endurance (3 -> 8 min, 10 -> 6 min, 18 -> 5 min, piecewise linear) and is centred on
 * the midpoint of vanilla's clear-weather night ({@link #NIGHT_START} to {@link #NIGHT_END}). Bed sleep credits 1.0
 * per tick and rough sleep credits 0.5 per tick; a civilian is rested once {@code sleptTicks >= need} and cannot sleep
 * longer to make up for a rough night. Time skips and unloaded time are caught up in O(1) via {@link #catchUp}.
 */
public final class CivilianSleep {

    public static final long DAY_LENGTH = 24_000L;
    /** Vanilla clear-weather bed window start (ticks of day). */
    public static final int NIGHT_START = 12_542;
    /** Vanilla clear-weather bed window end (ticks of day, exclusive). */
    public static final int NIGHT_END = 23_460;
    /** Midpoint of the night window: (NIGHT_START + NIGHT_END) / 2. */
    public static final int NIGHT_MIDPOINT = (NIGHT_START + NIGHT_END) / 2;
    public static final float BED_CREDIT_RATE = 1.0F;
    public static final float ROUGH_CREDIT_RATE = 0.5F;
    public static final long NO_NIGHT = Long.MIN_VALUE;
    public static final long NO_DAY_TIME = Long.MIN_VALUE;

    private long sessionNight = NO_NIGHT;
    private long restedNight = NO_NIGHT;
    private int sleptTicks;
    private float creditTicks;
    private float lastNight = Float.NaN;
    private long lastDayTime = NO_DAY_TIME;

    /** Sleep need in ticks for a raw Endurance score (3..18), rounded to the nearest tick. */
    public static int neededTicks(int enduranceRaw) {
        int raw = Math.clamp(enduranceRaw, 3, 18);
        double minutes = raw <= 10
                ? 8.0 - 2.0 * (raw - 3) / 7.0
                : 6.0 - (raw - 10) / 8.0;
        return (int) Math.round(1200.0 * minutes);
    }

    public static int timeOfDay(long dayTime) {
        return (int) Math.floorMod(dayTime, DAY_LENGTH);
    }

    public static boolean isNight(long dayTime) {
        int t = timeOfDay(dayTime);
        return t >= NIGHT_START && t < NIGHT_END;
    }

    /** Index of the night containing {@code dayTime} (or the most recent night before daytime). */
    public static long nightIndex(long dayTime) {
        return Math.floorDiv(dayTime - NIGHT_START, DAY_LENGTH);
    }

    public static long nightStart(long nightIndex) {
        return nightIndex * DAY_LENGTH + NIGHT_START;
    }

    public static long nightEnd(long nightIndex) {
        return nightIndex * DAY_LENGTH + NIGHT_END;
    }

    /** Time-of-day bedtime so the civilian's sleep is centred on {@link #NIGHT_MIDPOINT}. */
    public static int bedtime(int need) {
        return NIGHT_MIDPOINT - need / 2;
    }

    /** Time-of-day wake time matching {@link #bedtime(int)}. */
    public static int wakeTime(int need) {
        return bedtime(need) + need;
    }

    public static long ownBedtime(long nightIndex, int need) {
        return nightIndex * DAY_LENGTH + bedtime(need);
    }

    public static long ownWakeTime(long nightIndex, int need) {
        return nightIndex * DAY_LENGTH + wakeTime(need);
    }

    /** True from the civilian's own bedtime until the end of the night. */
    public static boolean isPastBedtime(long dayTime, int need) {
        return isNight(dayTime) && timeOfDay(dayTime) >= bedtime(need);
    }

    public boolean hasOpenSession() {
        return sessionNight != NO_NIGHT;
    }

    public boolean isRestedFor(long night) {
        return restedNight == night;
    }

    /** True when it is at or past bedtime, still night, and this civilian has not yet finished sleeping tonight. */
    public boolean wantsToSleep(long dayTime, int need) {
        return isPastBedtime(dayTime, need) && !isRestedFor(nightIndex(dayTime));
    }

    /** Processes one active tick at {@code dayTime}. */
    public void tick(long dayTime, boolean lyingDown, boolean inBed, int need) {
        validateNeed(need);
        if (hasOpenSession() && (!isNight(dayTime) || nightIndex(dayTime) != sessionNight)) {
            finalise(need);
        }
        if (isNight(dayTime)) {
            long night = nightIndex(dayTime);
            if (!isRestedFor(night)) {
                if (sessionNight != night) {
                    openSession(night);
                }
                if (lyingDown) {
                    sleptTicks++;
                    creditTicks += inBed ? BED_CREDIT_RATE : ROUGH_CREDIT_RATE;
                    if (sleptTicks >= need) {
                        finalise(need);
                        restedNight = night;
                    }
                }
            }
        }
        lastDayTime = dayTime;
    }

    /**
     * Catches up skipped or unloaded time in {@code [from, to)} in O(1), crediting every tick inside the civilian's
     * own sleep period at the bed or rough rate and finalising at night boundaries.
     */
    public void catchUp(long from, long to, boolean hasBed, int need) {
        validateNeed(need);
        if (to <= from) {
            if (to < from) {
                if (hasOpenSession() && (!isNight(to) || nightIndex(to) != sessionNight)) {
                    clearSession();
                }
                lastDayTime = to;
            }
            return;
        }

        float rate = hasBed ? BED_CREDIT_RATE : ROUGH_CREDIT_RATE;
        long k0 = nightIndex(from);
        long kStart = from >= nightEnd(k0) ? k0 + 1 : k0;
        long kEnd = nightIndex(to - 1);

        if (hasOpenSession() && sessionNight < kStart) {
            finalise(need);
        }

        if (kStart <= kEnd) {
            processPartialNight(kStart, from, to, rate, need);
            if (kEnd > kStart) {
                long kLastComplete = to >= nightEnd(kEnd) ? kEnd : kEnd - 1;
                if (kLastComplete > kStart) {
                    lastNight = Math.min(1.0F, rate);
                    restedNight = kLastComplete;
                    clearSession();
                }
                if (kLastComplete < kEnd) {
                    processPartialNight(kEnd, from, to, rate, need);
                }
            }
        }
        lastDayTime = to;
    }

    private void processPartialNight(long night, long from, long to, float rate, int need) {
        if (isRestedFor(night)) {
            return;
        }
        if (sessionNight != night) {
            openSession(night);
        }
        long overlapStart = Math.max(from, ownBedtime(night, need));
        long overlapEnd = Math.min(to, ownWakeTime(night, need));
        long overlap = Math.max(0L, overlapEnd - overlapStart);
        if (overlap > 0L) {
            int remaining = Math.max(0, need - sleptTicks);
            int add = (int) Math.min(overlap, remaining);
            sleptTicks += add;
            creditTicks += add * rate;
        }
        if (sleptTicks >= need) {
            finalise(need);
            restedNight = night;
        } else if (to >= nightEnd(night)) {
            finalise(need);
        }
    }

    private void openSession(long night) {
        sessionNight = night;
        sleptTicks = 0;
        creditTicks = 0.0F;
    }

    private void finalise(int need) {
        lastNight = Math.clamp(creditTicks / need, 0.0F, 1.0F);
        clearSession();
    }

    private void clearSession() {
        sessionNight = NO_NIGHT;
        sleptTicks = 0;
        creditTicks = 0.0F;
    }

    private static void validateNeed(int need) {
        if (need <= 0) {
            throw new IllegalArgumentException("need must be positive: " + need);
        }
    }

    public long sessionNight() {
        return sessionNight;
    }

    public long restedNight() {
        return restedNight;
    }

    public int sleptTicks() {
        return sleptTicks;
    }

    public float creditTicks() {
        return creditTicks;
    }

    /** Finalised sleep quality from the most recent night in [0, 1], or {@code Float.NaN} before the first night. */
    public float lastNight() {
        return lastNight;
    }

    /** Debug setter for {@link #lastNight()}. */
    public void setLastNight(float value) {
        this.lastNight = Float.isNaN(value) ? Float.NaN : Math.clamp(value, 0.0F, 1.0F);
    }

    public long lastDayTime() {
        return lastDayTime;
    }

    public void setLastDayTime(long dayTime) {
        this.lastDayTime = dayTime;
    }

    public Saved save() {
        return new Saved(sessionNight, restedNight, sleptTicks, creditTicks, lastNight, lastDayTime);
    }

    /** Restores a saved snapshot verbatim; persistence callers handle missing or corrupt fields. */
    public static CivilianSleep restore(Saved saved) {
        CivilianSleep sleep = new CivilianSleep();
        sleep.sessionNight = saved.sessionNight();
        sleep.restedNight = saved.restedNight();
        sleep.sleptTicks = saved.sleptTicks();
        sleep.creditTicks = saved.creditTicks();
        sleep.lastNight = saved.lastNight();
        sleep.lastDayTime = saved.lastDayTime();
        return sleep;
    }

    public CivilianSleep copy() {
        return restore(save());
    }

    /** Plain values for persistence without any game or NBT dependency. */
    public record Saved(long sessionNight, long restedNight, int sleptTicks, float creditTicks,
                        float lastNight, long lastDayTime) {
    }
}
