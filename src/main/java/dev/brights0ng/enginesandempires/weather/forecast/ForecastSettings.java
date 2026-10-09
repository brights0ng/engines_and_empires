package dev.brights0ng.enginesandempires.weather.forecast;

/**
 * The forecaster's settings (phase 7b), from the server config's {@code [forecast]} section. Pure.
 *
 * @param delaySeconds  real seconds every forecast takes to arrive
 * @param days          days the longer forecast covers
 * @param dayStepTicks  ticks per step of the day forecast
 * @param weekStepTicks ticks per step of the longer forecast
 * @param dayCacheHours in-game hours a day forecast is reused for its area
 * @param weekCacheHours in-game hours a longer forecast is reused
 * @param queue         the most forecasts waiting at once
 */
public record ForecastSettings(int delaySeconds, int days, int dayStepTicks, int weekStepTicks, int dayCacheHours,
                               int weekCacheHours, int queue) {

    public static final ForecastSettings DEFAULT = new ForecastSettings(30, 4, 250, 1000, 1, 6, 8);

    public long stepTicks(Forecast.Product product) {
        return product == Forecast.Product.TODAY ? dayStepTicks : weekStepTicks;
    }

    /** How long a forecast for {@code product} is reused, ticks. */
    public long cacheTicks(Forecast.Product product) {
        return 1000L * (product == Forecast.Product.TODAY ? dayCacheHours : weekCacheHours);
    }
}
