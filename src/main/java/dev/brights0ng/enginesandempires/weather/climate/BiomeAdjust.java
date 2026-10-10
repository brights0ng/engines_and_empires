package dev.brights0ng.enginesandempires.weather.climate;

/**
 * What a biome changes about the climate it sits in (2026-10-10, climate from the world's noise; see
 * {@link NoiseClimate}). The climate itself comes from the temperature and humidity fields the world places biomes
 * with; a biome only adjusts it where it needs to, and says what the ground is and whether it never thaws. Lives in the
 * {@code biome_climate} data map ({@link ClimateDataMaps}). Pure.
 *
 * @param temperatureOffset C added to the noise's temperature (a jungle a little hotter than its band, say)
 * @param humidityOffset    added to the noise's humidity (a swamp wetter)
 * @param humidity          the humidity outright, 0-1, instead of the noise's (deserts and badlands dry whatever the
 *                          noise says); NaN to use the noise's (plus {@code humidityOffset})
 * @param surface           what the ground is; null to work it out from the biome's tags
 * @param frozen            whether it never thaws (Bright, 2026-10-05 and 2026-10-10: biomes with ice or powder snow
 *                          that can't regrow by water freezing or new snow): the air there stays below freezing
 */
public record BiomeAdjust(double temperatureOffset, double humidityOffset, double humidity,
                          BiomeClimate.Surface surface, boolean frozen) {

    /** No adjustment, ground from the tags, thaws normally. */
    public static final BiomeAdjust NONE = new BiomeAdjust(0, 0, Double.NaN, null, false);

    public boolean overridesHumidity() {
        return !Double.isNaN(humidity);
    }

    /** The same adjustment with its surface filled in (from the biome's tags) if it didn't name one. */
    public BiomeAdjust withSurface(BiomeClimate.Surface fallback) {
        return surface != null ? this : new BiomeAdjust(temperatureOffset, humidityOffset, humidity, fallback, frozen);
    }

    /** The same adjustment, never thawing. */
    public BiomeAdjust asFrozen() {
        return frozen ? this : new BiomeAdjust(temperatureOffset, humidityOffset, humidity, surface, true);
    }
}
