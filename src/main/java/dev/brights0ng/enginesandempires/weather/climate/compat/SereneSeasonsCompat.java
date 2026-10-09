package dev.brights0ng.enginesandempires.weather.climate.compat;

import net.minecraft.world.level.Level;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;

/**
 * Serene Seasons' calendar, read through its API. Only ever loaded once Serene Seasons is known to be present
 * ({@code weather/climate/SeasonSource}), so the pack still runs without it.
 */
public final class SereneSeasonsCompat {

    /** How far through Serene Seasons' year {@code level} is, 0-1 (0 = start of early spring). */
    public static double yearFraction(Level level) {
        ISeasonState state = SeasonHelper.getSeasonState(level);
        if (state == null || state.getCycleDuration() <= 0) {
            return Double.NaN;
        }
        return (double) Math.floorMod(state.getSeasonCycleTicks(), state.getCycleDuration()) / state.getCycleDuration();
    }

    /** Serene Seasons' sub-season name, for debug output. */
    public static String subSeason(Level level) {
        ISeasonState state = SeasonHelper.getSeasonState(level);
        return state == null ? "unknown" : state.getSubSeason().toString();
    }

    /** How long Serene Seasons' year is, ticks (0 if unknown). */
    public static long cycleTicks(Level level) {
        ISeasonState state = SeasonHelper.getSeasonState(level);
        return state == null ? 0 : Math.max(0, state.getCycleDuration());
    }

    private SereneSeasonsCompat() {
    }
}
