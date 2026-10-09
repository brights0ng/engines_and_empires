package dev.brights0ng.enginesandempires.weather.climate;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.climate.compat.SereneSeasonsCompat;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Where in the year it is: Serene Seasons' calendar when it is installed, otherwise no seasons (a neutral year: every
 * day is the year's mean). The pack ships Serene Seasons; the dev game tests run without it.
 */
public final class SeasonSource {

    private static Boolean loaded;
    private static boolean warned;

    /** Whether Serene Seasons drives the year. */
    public static boolean available() {
        if (loaded == null) {
            loaded = ModList.get() != null && ModList.get().isLoaded("sereneseasons");
        }
        return loaded;
    }

    /** How far through the year {@code level} is, 0-1 (0 = start of spring), or NaN without seasons. */
    public static double yearFraction(Level level) {
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
            return 0;
        }
        try {
            return SereneSeasonsCompat.cycleTicks(level);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private SeasonSource() {
    }
}
