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
 */
public final class Baseline {

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
        double w = Math.max(0, Math.min(1, localWeight));
        double mean = (1 - w) * regional.mean() + w * local.mean();
        double seasonal = ((1 - w) * regional.swing() + w * local.swing()) * season;
        double humidity = (1 - w) * regional.humidity() + w * local.humidity();
        double diurnal = ClimateCurves.diurnalRange(humidity) * diurnalFactor;
        double t = mean + seasonal + band + anomaly + diurnal + height;
        boolean clamped = false;
        if (local.frozen() && t > BiomeClimate.FROZEN_MAX) {
            t = BiomeClimate.FROZEN_MAX;
            clamped = true;
        }
        return new Sample(t, humidity, regional, local, seasonal, band, anomaly, diurnal, height, clamped);
    }

    private Baseline() {
    }
}
