package dev.brights0ng.enginesandempires.weather.climate;

import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The air temperature anywhere in the world, in degrees C: the pack's one source of truth for it (rain or snow, snow
 * and ice, the client sync, and later Cold Sweat). See {@code claude/weather-backbone-plan.md}.
 *
 * <h2>Phase 1 (2026-10-05): the climate baseline</h2>
 * {@link Climate}: the regional and local biome climates, the season, the climate band, the time of day and height
 * cooling, with always-frozen biomes held below freezing. Phase 3 adds the atmosphere field's anomalies (air masses,
 * fronts).
 *
 * <h2>Height cooling</h2>
 * The standard atmosphere cools 0.0065 C per metre of height. At the pack's x0.2 scale a block stands for 5 m, so the
 * server config's scale (default 5) gives 0.0325 C per block above sea level, cooling capped at
 * {@code maxHeightCooling} (default 30 C); below sea level the air warms at the same rate, capped at
 * {@link #MAX_WARMING}. (Bright, 2026-10-04: x0.2, to be toned down if Tectonic's mountains get too much snow.)
 */
public final class Temperature {

    /** The standard atmosphere's cooling per metre of height, C. */
    public static final double LAPSE_PER_METRE = 0.0065;
    /** The most the air warms below sea level, C. */
    public static final double MAX_WARMING = 14;

    /** How much warmer (negative: colder) than at sea level the air is at height {@code y}, C. */
    public static double heightCorrection(int seaLevel, double y, double scale, double maxCooling) {
        double c = (seaLevel - y) * LAPSE_PER_METRE * scale;
        return Math.max(-maxCooling, Math.min(MAX_WARMING, c));
    }

    /** {@link #heightCorrection} with the server config's values. */
    public static double heightCorrection(int seaLevel, double y) {
        return heightCorrection(seaLevel, y, WeatherConfig.heightCoolingScale(), WeatherConfig.maxHeightCooling());
    }

    /** The air temperature at {@code pos}, C. */
    public static double at(ServerLevel level, BlockPos pos) {
        return Climate.sample(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5).temperature();
    }

    /** The air temperature at sea level over (x, z), C (what the client sync's grid carries). */
    public static double atSeaLevel(ServerLevel level, int x, int z) {
        return Climate.sample(level, x + 0.5, level.getSeaLevel(), z + 0.5).temperature();
    }

    private Temperature() {
    }
}
