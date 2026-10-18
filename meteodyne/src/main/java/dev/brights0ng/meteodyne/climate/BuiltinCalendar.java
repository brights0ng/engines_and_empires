package dev.brights0ng.meteodyne.climate;

import java.util.Locale;

/**
 * The year without a season provider (split seam S11, Bright's "very simple" calendar): the year runs with the level's
 * day time, {@code yearFraction = (dayTime mod yearTicks) / yearTicks}, starting with spring at day time 0. No
 * sub-season names and no commands of its own. Off ({@code useBuiltinCalendar = false}), there are no seasons: every day
 * is the year's mean, as without Serene Seasons before the split.
 *
 * @param enabled   whether the calendar runs ({@code useBuiltinCalendar})
 * @param yearTicks the year's length, ticks ({@code yearTicks}), at least one
 */
public record BuiltinCalendar(boolean enabled, long yearTicks) {

    /** The default year: 12 sub-seasons of 6 days (Bright, 2026-10-12), the shape of Serene Seasons' default. */
    public static final long DEFAULT_YEAR_TICKS = 12 * 6 * 24000L;

    /** The config's defaults: on, with the default year. */
    public static final BuiltinCalendar DEFAULT = new BuiltinCalendar(true, DEFAULT_YEAR_TICKS);

    public BuiltinCalendar {
        if (yearTicks <= 0) {
            throw new IllegalArgumentException("A year must last at least a tick: " + yearTicks);
        }
    }

    /** How far through the year day time {@code dayTime} is, 0-1, or NaN when the calendar is off. */
    public double yearFraction(long dayTime) {
        return enabled ? (double) Math.floorMod(dayTime, yearTicks) / yearTicks : Double.NaN;
    }

    /** The day of the year and how far through the year {@code dayTime} is, for debug output. */
    public String describe(long dayTime) {
        if (!enabled) {
            return "none (no season provider, and the built-in calendar is off)";
        }
        long day = Math.floorMod(dayTime, yearTicks) / 24000L + 1;
        long days = Math.max(1, (yearTicks + 23999L) / 24000L);
        return String.format(Locale.ROOT, "built-in calendar: day %d of %d", day, days);
    }
}
