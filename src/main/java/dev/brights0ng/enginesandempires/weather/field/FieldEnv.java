package dev.brights0ng.enginesandempires.weather.field;

/**
 * What the atmosphere field needs from the world, so the field itself stays pure and testable on made-up worlds.
 */
public interface FieldEnv {

    /**
     * The climate baseline at sea level over (x, z): the day's normal temperature (season and band included, no
     * day-night swing), C; the biome humidity 0-1; the surface ordinal (0 land, 1 forest, 2 water, 3 ice).
     */
    double[] climate(double x, double z);

    /** The wind over (x, z): {surfaceX, surfaceZ, aloftX, aloftZ}, m/s. */
    double[] wind(double x, double z);

    /** The air masses the weather systems push toward at (x, z): {offset from normal C, weight 0-1}. */
    double[] contact(double x, double z);

    /** How much warmer (negative: colder) the air is {@code elevation} blocks above sea level, C. */
    double heightCorrection(double elevation);

    /**
     * This environment narrowed to one tile's square (perf report 2026-10-05): the world's environment drops weather
     * systems too far away to matter there, so per-cell lookups loop over fewer. Default: unchanged.
     */
    default FieldEnv forArea(double minX, double minZ, double maxX, double maxZ) {
        return this;
    }
}
