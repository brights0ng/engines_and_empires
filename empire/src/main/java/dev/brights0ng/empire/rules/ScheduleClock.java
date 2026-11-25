// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Locale;

/**
 * Midnight-anchored {@code mm:ss} schedule clock for Empire civilians (zero Minecraft imports).
 *
 * <p>A full Minecraft day is {@value #DAY_TICKS_INT} ticks (20 real minutes). Schedule time {@code 00:00} ({@code 0}
 * ticks) is vanilla midnight ({@value #MIDNIGHT_OFFSET}); {@code 20:00} ({@value #DAY_TICKS_INT} ticks) is the next
 * midnight. Sunrise ({@code vanilla 0}) is {@code 05:00}, noon ({@code vanilla 6000}) is {@code 10:00}, and sunset
 * ({@code vanilla 12000}) is {@code 15:00}.
 */
public final class ScheduleClock {

    public static final long DAY_TICKS = 24_000L;
    public static final int DAY_TICKS_INT = 24_000;
    /** Vanilla dayTime offset for midnight (00:00 on the schedule clock). */
    public static final long MIDNIGHT_OFFSET = 18_000L;
    public static final int TICKS_PER_SECOND = 20;
    public static final int TICKS_PER_MINUTE = 1_200;

    /** Vanilla clear-weather bed window start ({@code 12,542}) mapped to schedule clock ({@code 15:27}). */
    public static final int NIGHT_START_TICK = 18_542;
    /** Vanilla clear-weather bed window end ({@code 23,460}) mapped to schedule clock ({@code 04:33}). */
    public static final int NIGHT_END_TICK = 5_460;
    /** Sunrise ({@code vanilla 0}) on the schedule clock ({@code 05:00}). */
    public static final int SUNRISE_TICK = 6_000;
    /** Noon ({@code vanilla 6,000}) on the schedule clock ({@code 10:00}). */
    public static final int NOON_TICK = 12_000;
    /** Sunset ({@code vanilla 12,000}) on the schedule clock ({@code 15:00}). */
    public static final int SUNSET_TICK = 18_000;

    /** Start of the allowed outdoor window ({@code 04:00}, Bright 2026-11-24). */
    public static final int OUTDOOR_START_TICK = mmss(4, 0);
    /** End of the allowed outdoor window ({@code 16:00}, Bright 2026-11-24). */
    public static final int OUTDOOR_END_TICK = mmss(16, 0);

    /** Midnight-to-midnight day index containing {@code dayTime}. */
    public static long scheduleDay(long dayTime) {
        return Math.floorDiv(dayTime - MIDNIGHT_OFFSET, DAY_TICKS);
    }

    /** Ticks elapsed since the most recent midnight ({@code 0..23_999}). */
    public static int timeOfDay(long dayTime) {
        return (int) Math.floorMod(dayTime - MIDNIGHT_OFFSET, DAY_TICKS);
    }

    /** True if {@code tickOfDay} falls inside the outdoor activity window ({@code 04:00..16:00}). */
    public static boolean isOutdoorTime(int tickOfDay) {
        int t = Math.floorMod(tickOfDay, DAY_TICKS_INT);
        return t >= OUTDOOR_START_TICK && t < OUTDOOR_END_TICK;
    }

    /**
     * Tonight's bedtime on the midnight-anchored clock, matching {@link CivilianSleep#bedtime(int)} to the exact tick
     * (centered on {@link CivilianSleep#NIGHT_MIDPOINT} = {@code 18,001}).
     */
    public static int tonightBedtimeTick(int sleepNeedTicks) {
        int clampedNeed = Math.clamp(sleepNeedTicks, 2, (DAY_TICKS_INT - NIGHT_START_TICK) * 2);
        long vanillaBedtime = CivilianSleep.bedtime(clampedNeed);
        int tick = (int) Math.floorMod(vanillaBedtime - MIDNIGHT_OFFSET, DAY_TICKS);
        return Math.clamp(tick, NIGHT_START_TICK, DAY_TICKS_INT - 1);
    }

    /** Morning wake tick for remaining sleep past midnight, capped at {@link #NIGHT_END_TICK} ({@code 04:33}). */
    public static int continueSleepEndTick(int remainingSleepTicks) {
        return Math.clamp(remainingSleepTicks, 0, NIGHT_END_TICK);
    }

    /** Converts minutes and seconds on the 20-minute Minecraft day into ticks. */
    public static int mmss(int minutes, int seconds) {
        return minutes * TICKS_PER_MINUTE + seconds * TICKS_PER_SECOND;
    }

    /** Formats a tick offset (clamped to {@code >= 0}) as {@code mm:ss}. */
    public static String format(int ticks) {
        int safe = Math.max(0, ticks);
        int totalSeconds = safe / TICKS_PER_SECOND;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private ScheduleClock() {
    }
}
