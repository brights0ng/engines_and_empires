package dev.brights0ng.meteodyne.climate;

import dev.brights0ng.meteodyne.rain.WeatherConfig;
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
 * The standard atmosphere cools 0.0065 C per metre of height. The server config gives the cooling directly, C per 100
 * blocks (default 5, Bright 2026-10-15: the pack's x0.2 scale, so 0.05 C per block above sea level: 12 C colder at
 * y=300, 22 C at y=500), capped at {@code maxHeightCooling} (default 30 C); below sea level the air warms at the same
 * rate, capped at {@link #MAX_WARMING}. (Was 3.25 C per 100 blocks from 2026-10-04; to be toned down if Tectonic's
 * mountains get too much snow.) The functions here take the cooling as a multiple of the standard atmosphere's
 * ({@link #scaleFor}).
 *
 * <h2>Underground (phase 8c, 2026-10-15)</h2>
 * {@link #at} adds {@link Underground} on top: under cover the air eases to the year's mean and warms with depth, with
 * cave biome rules. Only {@link #at} does this; the forecast, the field, the map and the climatology tool read
 * {@link Climate#sample} at the surface and are unchanged.
 */
public final class Temperature {

    /** The standard atmosphere's cooling per metre of height, C. */
    public static final double LAPSE_PER_METRE = 0.0065;
    /** The most the air warms below sea level, C. */
    public static final double MAX_WARMING = 14;

    /** The cooling as a multiple of the standard atmosphere's 0.0065 C per block, for {@code per100} C per 100 blocks. */
    public static double scaleFor(double per100) {
        return per100 / (100 * LAPSE_PER_METRE);
    }

    /** How much warmer (negative: colder) than at sea level the air is at height {@code y}, C. */
    public static double heightCorrection(int seaLevel, double y, double scale, double maxCooling) {
        double c = (seaLevel - y) * LAPSE_PER_METRE * scale;
        return Math.max(-maxCooling, Math.min(MAX_WARMING, c));
    }

    /** {@link #heightCorrection} with the server config's values. */
    public static double heightCorrection(int seaLevel, double y) {
        return heightCorrection(seaLevel, y, WeatherConfig.heightCoolingScale(), WeatherConfig.maxHeightCooling());
    }

    /** The air temperature at {@code pos}, C, underground included. */
    public static double at(ServerLevel level, BlockPos pos) {
        return reading(level, pos).temperature();
    }

    /** {@link #at} with every underground part, for the debug readout. */
    public static Underground.Reading reading(ServerLevel level, BlockPos pos) {
        double outside = Climate.sample(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5).temperature();
        return UndergroundProbe.read(level, pos, outside);
    }

    /** The air temperature at sea level over (x, z), C (what the client sync's grid carries). */
    public static double atSeaLevel(ServerLevel level, int x, int z) {
        return Climate.sample(level, x + 0.5, level.getSeaLevel(), z + 0.5).temperature();
    }

    private Temperature() {
    }
}
