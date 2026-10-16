package dev.brights0ng.enginesandempires.weather.climate;

/**
 * The climate baseline at a spot: what is normal there at this moment, before any weather (phase 1 of
 * {@code claude/weather-backbone-plan.md}). Pure maths; {@link Climate} gathers the inputs from the world.
 *
 * <p>Two scales (Bright, 2026-10-05): the temperature blends the regional climate (smoothed, {@link ClimateField}) with
 * the biome actually underfoot, by {@code localWeight}. A small snowy patch is then noticeably colder than the land
 * around it, though not as cold as a big snowy region.
 *
 * <pre>
 *   T = (1 - w) * (regional mean + regional swing * season)
 *     + w       * (local mean    + local swing    * season)
 *     + band offset + air-mass anomaly + day-night range(humidity) * day-night factor + height correction
 * </pre>
 * and in an always-frozen biome {@code T <= }{@link BiomeClimate#FROZEN_MAX} whatever the rest says.
 *
 * <p>The air-mass anomaly (phase 3) is the atmosphere field's departure from the normal: cold air behind a cold front,
 * a warm sector, a heat wave under a blocking high, mild coastal air.
 *
 * <p><b>Tropical wet and dry seasons</b> (weather phase 8b, 2026-10-14): in warm climates with small seasonal swings
 * ({@link #tropicalness}) the humidity rises by up to {@value #TROPICAL_SWING} at the height of the wet season and falls
 * as much at the height of the dry one ({@code tropicalWet}, from {@link ClimateCurves#tropicalWet}). Rainforests stay
 * humid with a wetter wet season, savannas dry right out; hot deserts (large swings) are left alone.
 */
public final class Baseline {

    /** How far the humidity moves at the height of the tropical wet or dry season, for fully tropical climates. */
    public static final double TROPICAL_SWING = 0.35;

    /**
     * How much of the seasonal temperature swing a fully tropical climate loses (8b follow-up round 4, 2026-10-14;
     * Bright chose option b): real tropics swing only ~2-3 C through the year, but the noise climate gave the savanna
     * ±5 C, which put its wet season (Serene Seasons' calendar: around the turn of the year) at ~20 C, too cool to hold
     * the water its storms need. Deserts and temperate climates (not tropical) keep their swing.
     */
    public static final double TROPICAL_SWING_DAMP = 0.6;

    /**
     * Every part of a baseline sample, for the temperature service and the debug readouts.
     *
     * @param temperature the result, C
     * @param humidity    the blended humidity, 0-1
     * @param regional    the smoothed regional climate
     * @param local       the biome underfoot's climate
     * @param seasonal    the blended seasonal offset, C
     * @param band        the climate band offset, C (0 in biome mode)
     * @param anomaly     the atmosphere field's air-mass anomaly, C
     * @param diurnal     the day-night offset, C
     * @param height      the height correction, C
     * @param clamped     whether the frozen clamp lowered it
     */
    public record Sample(double temperature, double humidity, ClimateField.Regional regional, BiomeClimate local,
                         double seasonal, double band, double anomaly, double diurnal, double height,
                         boolean clamped) {

        /** The same sample without the day-night offset: the day's mean here. */
        public double dailyMean() {
            return temperature - (clamped ? 0 : diurnal);
        }
    }

    public static Sample combine(ClimateField.Regional regional, BiomeClimate local, double localWeight,
                                 double season, double band, double diurnalFactor, double height) {
        return combine(regional, local, localWeight, season, band, 0, diurnalFactor, height);
    }

    public static Sample combine(ClimateField.Regional regional, BiomeClimate local, double localWeight,
                                 double season, double band, double anomaly, double diurnalFactor, double height) {
        return combine(regional, local, localWeight, season, band, anomaly, diurnalFactor, height, 0);
    }

    /** As above, with the tropical wet season factor {@code tropicalWet} (-1 height of the dry season to +1 wet). */
    public static Sample combine(ClimateField.Regional regional, BiomeClimate local, double localWeight,
                                 double season, double band, double anomaly, double diurnalFactor, double height,
                                 double tropicalWet) {
        double w = Math.max(0, Math.min(1, localWeight));
        double mean = (1 - w) * regional.mean() + w * local.mean();
        double swing = (1 - w) * regional.swing() + w * local.swing();
        double humidity = (1 - w) * regional.humidity() + w * local.humidity();
        // How tropical, from the climate as the noise gives it (before any damping).
        double felt = swing - ClimateCurves.BAND_SEASONAL * band;
        double tropical = tropicalness(mean + band, felt);
        double seasonal = swing * (LEGACY_SWING ? 1 : 1 - TROPICAL_SWING_DAMP * tropical) * season;
        if (tropicalWet != 0) {
            // The band counts toward how warm the climate is (a jungle in a cold band isn't tropical) and how much it
            // swings: the band strengthens in winter (ClimateCurves.BAND_SEASONAL), which evens out the warm side's
            // year and sharpens the cold side's.
            humidity = Math.max(0, Math.min(1,
                    humidity + tropical * TROPICAL_SWING * tropicalWet));
        }
        double diurnal = ClimateCurves.diurnalRange(humidity) * diurnalFactor;
        double t = mean + seasonal + band + anomaly + diurnal + height;
        boolean clamped = false;
        if (local.frozen() && t > BiomeClimate.FROZEN_MAX) {
            t = BiomeClimate.FROZEN_MAX;
            clamped = true;
        }
        return new Sample(t, humidity, regional, local, seasonal, band, anomaly, diurnal, height, clamped);
    }

    /**
     * How tropical a climate with mean {@code mean} (C) and seasonal swing {@code swing} (C) is, 0-1: fully from a 24 C
     * mean with a swing of 5 C or less (the warm band: rainforest, savanna, blended with their surroundings); none below
     * a 19 C mean or from a 9 C swing (temperate climates at 11 C, hot deserts at 10 C).
     */
    public static double tropicalness(double mean, double swing) {
        return smooth((mean - 19) / 5) * smooth((9 - swing) / 4);
    }

    /** Debug only: {@code -Deae.legacy8b=true} keeps the full tropical swing, for before/after runs. */
    static final boolean LEGACY_SWING = Boolean.getBoolean("eae.legacy8b");

    private static double smooth(double x) {
        double c = Math.max(0, Math.min(1, x));
        return c * c * (3 - 2 * c);
    }

    private Baseline() {
    }
}
