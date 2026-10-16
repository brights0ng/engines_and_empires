package dev.brights0ng.enginesandempires.weather.climate;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.climate.compat.SereneSeasonsCompat;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Where in the year it is: Serene Seasons' calendar when it is installed, otherwise no seasons (a neutral year: every
 * day is the year's mean). The pack ships Serene Seasons; the dev game tests run without it.
 *
 * <h2>Debug override (phase 8a)</h2>
 * The climatology run ({@code weather/forecast/Climatology}) runs the weather through a whole year without moving
 * Serene Seasons' calendar, so it sets a year position here that replaces the calendar everywhere the pack reads the
 * season (climate normals, weather systems, field, forecasts). Not saved; the run clears it when it ends and on server
 * stop.
 */
public final class SeasonSource {

    /** The year's length while overridden without Serene Seasons: 12 sub-seasons of 6 days (Bright, 2026-10-12). */
    public static final long DEFAULT_YEAR_TICKS = 12 * 6 * 24000L;

    private static Boolean loaded;
    private static boolean warned;
    /** The overriding year position, 0-1, or NaN when Serene Seasons' calendar applies. */
    private static volatile double override = Double.NaN;

    /** Whether Serene Seasons drives the year. */
    public static boolean available() {
        if (loaded == null) {
            loaded = ModList.get() != null && ModList.get().isLoaded("sereneseasons");
        }
        return loaded;
    }

    /** Replaces the calendar with year position {@code fraction} (wrapped into 0-1) until {@link #clearOverride}. */
    public static void setOverride(double fraction) {
        override = fraction - Math.floor(fraction);
    }

    /** Goes back to Serene Seasons' calendar. */
    public static void clearOverride() {
        override = Double.NaN;
    }

    /** Whether a debug override replaces the calendar. */
    public static boolean overridden() {
        return !Double.isNaN(override);
    }

    /** How far through the year {@code level} is, 0-1 (0 = start of spring), or NaN without seasons. */
    public static double yearFraction(Level level) {
        double o = override;
        if (!Double.isNaN(o)) {
            return o;
        }
        if (!available()) {
            return Double.NaN;
        }
        try {
            return SereneSeasonsCompat.yearFraction(level);
        } catch (RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                EnginesAndEmpiresMod.LOGGER.warn("Climate: couldn't read Serene Seasons' calendar; no seasons", e);
            }
            return Double.NaN;
        }
    }

    /** The season's name for debug output. */
    public static String describe(Level level) {
        double o = override;
        if (!Double.isNaN(o)) {
            return String.format(java.util.Locale.ROOT, "overridden: %.1f%% through the year (climatology run)", 100 * o);
        }
        if (!available()) {
            return "none (Serene Seasons isn't installed)";
        }
        try {
            return SereneSeasonsCompat.subSeason(level);
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }

    /** How long the year is, ticks, or 0 without seasons (forecasts project the season forward with it). */
    public static long yearTicks(Level level) {
        if (!available()) {
            return overridden() ? DEFAULT_YEAR_TICKS : 0;
        }
        try {
            long ticks = SereneSeasonsCompat.cycleTicks(level);
            return ticks <= 0 && overridden() ? DEFAULT_YEAR_TICKS : ticks;
        } catch (RuntimeException | LinkageError e) {
            return overridden() ? DEFAULT_YEAR_TICKS : 0;
        }
    }

    private SeasonSource() {
    }
}
