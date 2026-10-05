package dev.brights0ng.enginesandempires.weather.climate;

import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The air temperature anywhere in the world, in degrees C: the pack's one source of truth for it (rain or snow, snow
 * and ice, the client sync, and later Cold Sweat). See {@code claude/weather-backbone-plan.md}.
 *
 * <h2>Phase 0 (2026-10-05): baseline only</h2>
 * The biome's own temperature mapped to degrees C, plus height cooling. Phase 1 replaces the biome mapping with the
 * smoothed climate baseline (biome climate table, seasons, day and night); phase 3 adds the atmosphere field's
 * anomalies (air masses, fronts).
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

    /**
     * Degrees C per unit of vanilla biome temperature, and the vanilla value that maps to 0 C. Vanilla snows below
     * 0.15, so that is freezing; plains (0.8) come out at 13 C, desert (2.0) 37 C, snowy plains (0.0) -3 C.
     */
    static final double C_PER_VANILLA = 20;
    static final double VANILLA_FREEZING = 0.15;

    /** How much warmer (negative: colder) than at sea level the air is at height {@code y}, C. */
    public static double heightCorrection(int seaLevel, double y, double scale, double maxCooling) {
        double c = (seaLevel - y) * LAPSE_PER_METRE * scale;
        return Math.max(-maxCooling, Math.min(MAX_WARMING, c));
    }

    /** {@link #heightCorrection} with the server config's values. */
    public static double heightCorrection(int seaLevel, double y) {
        return heightCorrection(seaLevel, y, WeatherConfig.heightCoolingScale(), WeatherConfig.maxHeightCooling());
    }

    /** A vanilla biome temperature as degrees C at sea level. */
    public static double fromVanilla(float vanilla) {
        return (vanilla - VANILLA_FREEZING) * C_PER_VANILLA;
    }

    /** The air temperature at {@code pos}, C. */
    public static double at(ServerLevel level, BlockPos pos) {
        return atSeaLevel(level, pos.getX(), pos.getZ()) + heightCorrection(level.getSeaLevel(), pos.getY());
    }

    /**
     * The air temperature at sea level over (x, z), C. The biome is read at the ground where the chunk is loaded (so a
     * cave biome under a mountain doesn't count), otherwise at sea level from the biome source.
     */
    public static double atSeaLevel(ServerLevel level, int x, int z) {
        int y = level.getSeaLevel();
        BlockPos column = new BlockPos(x, y, z);
        if (level.hasChunkAt(column)) {
            y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
        }
        Biome biome = level.getBiome(new BlockPos(x, y, z)).value();
        return fromVanilla(biome.getBaseTemperature());
    }

    private Temperature() {
    }
}
