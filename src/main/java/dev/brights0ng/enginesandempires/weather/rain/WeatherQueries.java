package dev.brights0ng.enginesandempires.weather.rain;

import dev.brights0ng.enginesandempires.weather.ships.ShipCover;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

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
 *
 * Ships (phase 5d): a ship overhead is a roof like any other; a ship's own block (in Sable's plot grid) is asked where
 * the ship really is.
 */
public final class WeatherQueries {

    /** Whether it is raining (not snowing) at {@code pos}, by vanilla's rules. */
    public static boolean isRainingAt(Level level, BlockPos pos) {
        // Asked for every entity every tick (Entity.isInRain), so the no-clouds case returns before any block lookups.
        if (LocalWeather.clouds(level).isEmpty()) {
            return false;
        }
        Vec3 at = where(level, pos);
        return at != null && LocalWeather.at(level, at.x, at.y, at.z).raining() && noShipOver(level, at);
    }

    /** Whether a thunder cloud is raining or snowing on {@code pos}, by vanilla's rules. */
    public static boolean isThunderingAt(Level level, BlockPos pos) {
        if (LocalWeather.clouds(level).isEmpty()) {
            return false;
        }
        Vec3 at = where(level, pos);
        return at != null && LocalWeather.at(level, at.x, at.y, at.z).thundering() && noShipOver(level, at);
    }

    /** What is falling on the open air above (x, y, z): at the top of the column if that is higher. */
    public static LocalWeather.Here overhead(Level level, double x, double y, double z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        return LocalWeather.at(level, x, Math.max(y, top) + 0.5, z);
    }

    /**
     * Where to ask about {@code pos} (its centre, in the world), or null if it is roofed over by the world. A ship's
     * block is asked where the ship is.
     */
    private static Vec3 where(Level level, BlockPos pos) {
        if (ShipCover.inPlot(level, pos)) {
            Vec3 w = ShipCover.worldCentre(level, pos);
            if (w == null) {
                return null;
            }
            BlockPos p = BlockPos.containing(w);
            return level.isLoaded(p) && level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) > w.y
                    ? null : w;
        }
        return open(level, pos) ? Vec3.atCenterOf(pos) : null;
    }

    /** No ship over the spot (asked last: it is the dearest test, and rarely needed). */
    private static boolean noShipOver(Level level, Vec3 at) {
        return !ShipCover.covered(level, at.x, at.y + 0.5, at.z);
    }

    /** Vanilla's test: the sky can be seen and nothing that stops rain is above. */
    private static boolean open(Level level, BlockPos pos) {
        return level.canSeeSky(pos) && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY();
    }

    private WeatherQueries() {
    }
}
