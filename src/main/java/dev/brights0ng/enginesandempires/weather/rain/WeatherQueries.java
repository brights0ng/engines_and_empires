package dev.brights0ng.enginesandempires.weather.rain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Vanilla's weather questions answered by position from {@link LocalWeather} (the rule, Bright 2026-10-02: rain drawn at
 * a spot is rain reported there; rain from a thunder cloud is thunder there).
 *
 * <ul>
 *   <li>{@link #isRainingAt} / {@link #isThunderingAt}: vanilla's rules (open sky, nothing above that stops rain, rain
 *       not snow), used for {@code Level.isRainingAt} (wet entities, fires going out, tridents).</li>
 *   <li>{@link #overhead}: what is falling on the open air above a spot (the top of its column), so a roof doesn't stop
 *       the sky darkening or the rain fog, as in vanilla.</li>
 * </ul>
 */
public final class WeatherQueries {

    /** Whether it is raining (not snowing) at {@code pos}, by vanilla's rules. */
    public static boolean isRainingAt(Level level, BlockPos pos) {
        // Asked for every entity every tick (Entity.isInRain), so the no-clouds case returns before any block lookups.
        return !LocalWeather.clouds(level).isEmpty() && open(level, pos)
                && LocalWeather.at(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5).raining();
    }

    /** Whether a thunder cloud is raining or snowing on {@code pos}, by vanilla's rules. */
    public static boolean isThunderingAt(Level level, BlockPos pos) {
        return !LocalWeather.clouds(level).isEmpty() && open(level, pos)
                && LocalWeather.at(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5).thundering();
    }

    /** What is falling on the open air above (x, y, z): at the top of the column if that is higher. */
    public static LocalWeather.Here overhead(Level level, double x, double y, double z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        return LocalWeather.at(level, x, Math.max(y, top) + 0.5, z);
    }

    /** Vanilla's test: the sky can be seen and nothing that stops rain is above. */
    private static boolean open(Level level, BlockPos pos) {
        return level.canSeeSky(pos) && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY();
    }

    private WeatherQueries() {
    }
}
