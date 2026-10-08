package dev.brights0ng.enginesandempires.weather.climate;

import java.util.Locale;

/**
 * One biome's climate: what is normal there before any weather. The values for vanilla biomes are drafted from
 * real-world analogue climates (plains as temperate grassland, snowy taiga as boreal forest, and so on) and live in the
 * {@code biome_climate} data map ({@link ClimateDataMaps}), so they can be tuned or extended for modded biomes without
 * code. Biomes not in the map get {@link #fallback}. Pure Java (the codec is in {@link ClimateDataMaps}).
 *
 * <p>Temperatures are at <b>sea level</b>: height cooling is added on top ({@link Temperature#heightCorrection}), so a
 * mountain biome's values are what its air would be at sea level, which is why peaks are only somewhat colder here.
 *
 * @param mean     annual mean temperature at sea level, C
 * @param swing    seasonal half-range, C: midsummer is {@code mean + swing}, midwinter {@code mean - swing}
 * @param humidity how moist the air is, 0 (desert) to 1 (rainforest); also sets how big the day-night swing is
 * @param surface  what the ground is, for later phases (evaporation, friction)
 * @param frozen   whether it never thaws (Bright, 2026-10-05: biomes whose ice can't regrow by water freezing):
 *                 the air there stays below freezing whatever the season or time of day
 */
public record BiomeClimate(double mean, double swing, double humidity, Surface surface, boolean frozen) {

    /** The warmest an always-frozen biome's air gets, C. */
    public static final double FROZEN_MAX = -2;

    /** What the ground is. */
    public enum Surface {
        LAND, FOREST, WATER, ICE;

        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The surface named {@code name} (as in the data map), or null. */
        public static Surface byName(String name) {
            for (Surface s : values()) {
                if (s.getSerializedName().equals(name)) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * A climate for a biome the data map doesn't list (modded ones), from its vanilla temperature and downfall:
     * vanilla's snow line (0.15) is freezing and each unit is 20 C; drier biomes swing more between seasons.
     */
    public static BiomeClimate fallback(float vanillaTemperature, float downfall, Surface surface) {
        double mean = Math.max(-30, Math.min(32, (vanillaTemperature - 0.15) * 20));
        double humidity = Math.max(0, Math.min(1, downfall));
        double swing = 6 + 8 * (1 - humidity);
        return new BiomeClimate(mean, swing, humidity, surface, false);
    }
}
