package dev.brights0ng.enginesandempires.weather.forecast;

import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.sim.SystemsSim;

/**
 * Everything a forecast run needs, copied from the world on the server thread (phase 7b), so the run itself can go on
 * a background thread without touching the level. The systems and field are the forecast's own copies.
 *
 * @param product      which forecast
 * @param x            the region's centre, x
 * @param z            the region's centre, z
 * @param seed         the world seed (gust phases)
 * @param seaLevel     the world's sea level
 * @param systems      a forecast copy of the weather systems ({@link SystemsSim#forForecast})
 * @param field        a copy of the atmosphere field around the region ({@link AtmosphereField#copyDomain})
 * @param time         the simulation time the forecast starts from, ticks
 * @param dayTime      Minecraft day time at that moment
 * @param yearFraction how far through the year it is (NaN without seasons)
 * @param yearTicks    how long the year is, ticks (0 without seasons)
 * @param stepTicks    how far each forecast step goes, ticks
 * @param days         how many days the 4-day forecast covers
 * @param heightScale  the height-cooling scale ({@code Temperature}; the config's, read on the server thread)
 * @param maxCooling   the most height cooling, C
 */
public record ForecastSnapshot(Forecast.Product product, double x, double z, long seed, int seaLevel,
                               SystemsSim systems, AtmosphereField field, long time, long dayTime,
                               double yearFraction, long yearTicks, long stepTicks, int days, double heightScale,
                               double maxCooling) {

    /** The region the forecast must hold field for, relative to its centre: {west, east, north/south}, blocks. */
    public static double[] domain(Forecast.Product product) {
        return product == Forecast.Product.TODAY ? new double[]{12_000, 4_000, 6_000}
                : new double[]{24_000, 8_000, 12_000};
    }
}
