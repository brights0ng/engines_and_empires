package dev.brights0ng.meteodyne.climate;

import net.minecraft.world.level.Level;

/**
 * A calendar from another mod (the pack registers Serene Seasons' when it is installed) that tells {@link SeasonSource}
 * where in the year a level is. Registered at mod setup with {@link SeasonSource#register}; it replaces the built-in
 * calendar.
 */
public interface SeasonProvider {

    /** How far through the year {@code level} is, 0-1 (0 = start of spring), or NaN if it can't tell. */
    double yearFraction(Level level);

    /** The season's name for debug output. */
    String describe(Level level);

    /** How long the year is, ticks, or 0 if unknown (forecasts project the season forward with it). */
    default long yearTicks(Level level) {
        return 0;
    }
}
