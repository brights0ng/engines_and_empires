package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import dev.brights0ng.enginesandempires.weather.rain.Precip;

/**
 * A finished forecast for one spot (phase 7b; a point forecast since 7d of {@code claude/weather-backbone-phase7.md}):
 * the parts of one product, read from a region's shared run ({@link ForecastGrid#at}). Values are kept in the
 * simulation's units (C, m/s, hPa); {@link ForecastText} turns them into what players read (F, m/s with a word).
 * Immutable once made.
 *
 * @param product the kind of forecast
 * @param x       the spot it is for, x
 * @param z       the spot it is for, z
 * @param issued  the simulation time it was made from, ticks
 * @param dayTime Minecraft day time it was made at
 * @param parts   the 6-hour periods (day forecast) or days (4-day forecast), in order
 */
public record Forecast(Product product, double x, double z, long issued, long dayTime, List<Outlook> parts) {

    /** The two forecasts ("LODs"). */
    public enum Product {
        /** The next day in four 6-hour periods, fine steps over a smaller area: quite accurate. */
        TODAY("today", 512, 128),
        /** Days 1-4 (day 1 = tomorrow) as daily summaries, coarse steps over a wide area: less sure further out. */
        WEEK("week", 2048, 256);

        public final String id;
        /** The region a shared run is made for, blocks (everyone inside one region shares the run, not the answer). */
        public final int region;
        /**
         * Blocks between the run's readings; a spot's forecast reads the 3 x 3 readings around it, so its rain and
         * kind come from within about this far (7d).
         */
        public final int spacing;

        Product(String id, int region, int spacing) {
            this.id = id;
            this.region = region;
            this.spacing = spacing;
        }
    }

    /** Weather worth calling out. */
    public enum Event {
        THUNDERSTORM("thunderstorms"),
        HAIL("hail"),
        BLIZZARD("blizzard conditions"),
        FREEZING_RAIN("freezing rain"),
        GALE("gales");

        public final String words;

        Event(String words) {
            this.words = words;
        }
    }

    /**
     * One period or day.
     *
     * @param label       what it covers, for display ("Afternoon (12pm-6pm)", "Tomorrow (day 154)")
     * @param start       simulation time it starts, ticks
     * @param end         simulation time it ends, ticks
     * @param tMin        lowest temperature, C
     * @param tMax        highest temperature, C
     * @param cover       mean sky cover, 0-1
     * @param chance      chance of precipitation in the region, 0-1
     * @param kind        what would fall (null when the chance is negligible)
     * @param intensity   how hard it falls when it does, 0-1 (of the hardest rain)
     * @param windSpeed   mean surface wind, m/s
     * @param windFrom    direction the wind blows from, degrees clockwise from north (NaN when calm)
     * @param gust        strongest gusts, m/s
     * @param pressureStart pressure at the start, hPa
     * @param pressureEnd pressure at the end, hPa
     * @param thunderChance chance of a thunderstorm in the region, 0-1
     * @param events      weather worth calling out
     */
    public record Outlook(String label, long start, long end, double tMin, double tMax, double cover, double chance,
                          Precip kind, double intensity, double windSpeed, double windFrom, double gust,
                          double pressureStart, double pressureEnd, double thunderChance, Set<Event> events) {

        public Outlook {
            events = events.isEmpty() ? EnumSet.noneOf(Event.class) : EnumSet.copyOf(events);
        }
    }
}
