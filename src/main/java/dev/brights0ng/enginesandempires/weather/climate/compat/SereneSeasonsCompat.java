package dev.brights0ng.enginesandempires.weather.climate.compat;

import dev.brights0ng.meteodyne.climate.SeasonProvider;
import dev.brights0ng.meteodyne.climate.SeasonSource;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;

/**
 * Serene Seasons' calendar, read through its API, as Meteodyne's {@link SeasonProvider} (split seam S11). Registered
 * only when Serene Seasons is installed (see {@link #registerIfLoaded}); without it Meteodyne's built-in calendar runs
 * the year. Only {@link Calendar} names Serene Seasons' classes, so the pack still runs without it.
 */
public final class SereneSeasonsCompat {

    public static final String MOD_ID = "sereneseasons";

    /** Makes Serene Seasons' calendar the season provider if it is present. Safe to call either way. */
    public static void registerIfLoaded() {
        if (ModList.get().isLoaded(MOD_ID)) {
            SeasonSource.register(new Calendar());
        }
    }

    private static final class Calendar implements SeasonProvider {

        /** How far through Serene Seasons' year {@code level} is, 0-1 (0 = start of early spring). */
        @Override
        public double yearFraction(Level level) {
            ISeasonState state = SeasonHelper.getSeasonState(level);
            if (state == null || state.getCycleDuration() <= 0) {
                return Double.NaN;
            }
            return (double) Math.floorMod(state.getSeasonCycleTicks(), state.getCycleDuration()) / state.getCycleDuration();
        }

        /** Serene Seasons' sub-season name, for debug output. */
        @Override
        public String describe(Level level) {
            ISeasonState state = SeasonHelper.getSeasonState(level);
            return state == null ? "unknown" : state.getSubSeason().toString();
        }

        /** How long Serene Seasons' year is, ticks (0 if unknown). */
        @Override
        public long yearTicks(Level level) {
            ISeasonState state = SeasonHelper.getSeasonState(level);
            return state == null ? 0 : Math.max(0, state.getCycleDuration());
        }
    }

    private SereneSeasonsCompat() {
    }
}
