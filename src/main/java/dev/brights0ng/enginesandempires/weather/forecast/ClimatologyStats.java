package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.brights0ng.enginesandempires.weather.rain.Precip;

/**
 * A climatology run's statistics for one spot (phase 8a of {@code claude/weather-backbone-phase8.md}). Pure.
 *
 * <p>Hourly readings ({@link Hour}) are gathered into days (midnight to midnight) and the days into seasons, by where
 * in the year each day starts (Serene Seasons' quarters: spring from 0, summer from 1/4, autumn from 1/2, winter from
 * 3/4).
 *
 * <h2>What counts</h2>
 * <ul>
 *   <li><b>Wet hours</b> are expected hours of precipitation at the spot: each hour adds the share of the spot's
 *       readings under rain or snow (0-1), so half the neighbourhood under a shower for an hour adds half an hour.</li>
 *   <li>A <b>wet day</b> has at least {@value #WET_DAY_HOURS} wet hour; the <b>longest dry spell</b> is the longest run
 *       of consecutive days in the season that aren't wet.</li>
 *   <li><b>Kind hours</b> split the wet hours by what falls; <b>thunder hours</b> are expected hours of a thunderstorm
 *       at the spot, counted like wet hours (the share of the readings under a thunderstorm; 8b follow-up,
 *       2026-10-14: before, any reading whose air <em>could</em> build a thunderstorm made the whole hour count, so
 *       a rainforest winter had 421 thunder hours against 44 wet ones). <b>Storm-nearby hours</b> keep that old
 *       measure (storm-capable air anywhere in the neighbourhood) for comparison. <b>Hail-storm hours</b> are those
 *       with a storm strong enough to hail anywhere in it (live, such a storm hails only in bursts in its core, so
 *       this is how often hail is possible, not how long it falls).</li>
 *   <li>A <b>frost day</b> has a low below 0 C; a <b>gale hour</b> a mean wind of {@value #GALE_WIND} m/s or more.</li>
 *   <li><b>Day-to-day variability</b> (weather phase 8b) is the standard deviation of the change in daily mean
 *       temperature from one day to the next within a season (the seasonal trend itself barely shows in it).</li>
 *   <li>The <b>warmest and coldest weeks</b> are the 6-day stretches (one sub-season) with the highest and lowest mean.</li>
 *   <li>A <b>snowy day</b> has at least {@value #SNOW_DAY_HOURS} hours of snow, sleet or mixed; the <b>first snow</b> is
 *       the first snowy day after the warmest week, the <b>last snow</b> the last one before it.</li>
 * </ul>
 */
public final class ClimatologyStats {

    static final double WET_DAY_HOURS = 1.0;
    static final double GALE_WIND = ForecastChance.GALE_WIND;
    static final double SNOW_DAY_HOURS = 0.5;
    static final int WEEK_DAYS = 6;

    public enum Season {
        SPRING, SUMMER, AUTUMN, WINTER;

        /** The season at year position {@code yearFraction} (0 = start of spring; NaN counts as spring). */
        public static Season of(double yearFraction) {
            if (!Double.isFinite(yearFraction)) {
                return SPRING;
            }
            double y = yearFraction - Math.floor(yearFraction);
            return values()[Math.min(3, (int) (y * 4))];
        }

        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One hour's reading at the spot.
     *
     * @param day          the day it belongs to (any whole-day counter, midnight to midnight)
     * @param yearFraction where in the year it is, 0-1
     * @param t            the air temperature at the ground, C
     * @param wet          the share of the spot under precipitation, 0-1
     * @param kind         what falls (null when nothing does)
     * @param hail         whether a storm strong enough to hail is anywhere in the neighbourhood
     * @param thunderNear  whether storm-capable air is anywhere in the neighbourhood (the old thunder measure)
     * @param wind         the mean wind, m/s
     * @param thunder      the share of the spot under a thunderstorm, 0-1
     * @param heard        the chance a thunderstorm is within earshot this hour, 0-1 ({@link #withHeard})
     */
    public record Hour(long day, double yearFraction, double t, double wet, Precip kind, boolean hail,
                       boolean thunderNear, double wind, double thunder, double heard) {

        /** Without an earshot reading (it reads as thunder overhead). */
        public Hour(long day, double yearFraction, double t, double wet, Precip kind, boolean hail,
                    boolean thunderNear, double wind, double thunder) {
            this(day, yearFraction, t, wet, kind, hail, thunderNear, wind, thunder, thunder);
        }

        /** With the thunder share taken as all or nothing from {@code thunderNear} (tests). */
        public Hour(long day, double yearFraction, double t, double wet, Precip kind, boolean hail,
                    boolean thunderNear, double wind) {
            this(day, yearFraction, t, wet, kind, hail, thunderNear, wind, thunderNear ? 1 : 0);
        }

        /**
         * This hour with thunder heard from {@code storms} thunderstorms expected within earshot
         * ({@code ForecastRun.stormsWithin}): storms scattered at random, so the chance of at least one is
         * {@code 1 - exp(-storms)} (8b follow-up, 2026-10-14).
         */
        public Hour withHeard(double storms) {
            double h = storms > 0 ? 1 - Math.exp(-storms) : 0;
            return new Hour(day, yearFraction, t, wet, kind, hail, thunderNear, wind, thunder, Math.max(h, thunder));
        }
    }

    /** One day's totals. */
    public static final class Day {
        public final long day;
        public final Season season;
        public final double yearFraction;
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        double tSum;
        int hours;
        double wetHours;
        double maxWet;
        final EnumMap<Precip, Double> kinds = new EnumMap<>(Precip.class);
        int hailHours;
        double thunderHours;
        double heardHours;
        int nearHours;
        double windSum;
        double windMax;
        int galeHours;

        Day(long day, double yearFraction) {
            this.day = day;
            this.yearFraction = yearFraction;
            this.season = Season.of(yearFraction);
        }

        void add(Hour h) {
            low = Math.min(low, h.t());
            high = Math.max(high, h.t());
            tSum += h.t();
            hours++;
            double wet = Math.max(0, Math.min(1, h.wet()));
            wetHours += wet;
            maxWet = Math.max(maxWet, wet);
            if (h.kind() != null && wet > 0) {
                kinds.merge(h.kind(), wet, Double::sum);
            }
            if (h.hail()) {
                hailHours++;
            }
            thunderHours += Math.max(0, Math.min(1, h.thunder()));
            heardHours += Math.max(0, Math.min(1, h.heard()));
            if (h.thunderNear()) {
                nearHours++;
            }
            windSum += h.wind();
            windMax = Math.max(windMax, h.wind());
            if (h.wind() >= GALE_WIND) {
                galeHours++;
            }
        }

        public double low() {
            return low;
        }

        public double high() {
            return high;
        }

        public double mean() {
            return hours == 0 ? Double.NaN : tSum / hours;
        }

        public double wetHours() {
            return wetHours;
        }

        public boolean wet() {
            return wetHours >= WET_DAY_HOURS;
        }

        public boolean frost() {
            return low < 0;
        }

        public int hours() {
            return hours;
        }

        public double kindHours(Precip p) {
            return kinds.getOrDefault(p, 0.0);
        }

        /** Hours of snow, sleet and mixed. */
        public double snowHours() {
            return kindHours(Precip.SNOW) + kindHours(Precip.SLEET) + kindHours(Precip.MIXED);
        }

        public static final String CSV_HEADER = "day,season,year_pct,hours,t_low_C,t_high_C,t_mean_C,wet_hours,max_wet,"
                + "rain_h,mixed_h,snow_h,sleet_h,freezing_rain_h,hail_capable_h,thunder_h,wind_mean,wind_max,gale_h,"
                + "storm_nearby_h,thunder_heard_h";

        public String csv() {
            return String.format(Locale.ROOT, "%d,%s,%.1f,%d,%.1f,%.1f,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%.1f,%.1f,%d,%d,%.2f",
                    day, season.label(), 100 * yearFraction, hours, low, high, mean(), wetHours, maxWet,
                    kindHours(Precip.RAIN), kindHours(Precip.MIXED), kindHours(Precip.SNOW), kindHours(Precip.SLEET),
                    kindHours(Precip.FREEZING_RAIN), hailHours, thunderHours, hours == 0 ? 0 : windSum / hours, windMax,
                    galeHours, nearHours, heardHours);
        }
    }

    /**
     * A season's summary.
     *
     * @param days        days counted
     * @param mean        mean temperature, C
     * @param lowMean     mean daily low, C
     * @param highMean    mean daily high, C
     * @param coldest     the lowest reading, C
     * @param warmest     the highest reading, C
     * @param frostDays   days with a low below 0 C
     * @param wetDays     wet days
     * @param wetHours    expected hours of precipitation
     * @param kindHours   wet hours by kind
     * @param hailHours   hours with a storm strong enough to hail in the neighbourhood
     * @param thunderHours expected hours of a thunderstorm at the spot
     * @param windMean    mean wind, m/s
     * @param galeHours   hours at gale strength
     * @param longestDry  the longest run of dry days
     * @param nearHours   hours with storm-capable air anywhere in the neighbourhood (the old thunder measure)
     * @param heardHours  expected hours with a thunderstorm within earshot
     */
    public record Summary(int days, double mean, double lowMean, double highMean, double coldest, double warmest,
                          int frostDays, int wetDays, double wetHours, Map<Precip, Double> kindHours, int hailHours,
                          double thunderHours, double windMean, int galeHours, int longestDry, double variability,
                          int nearHours, double heardHours) {

        /** One line for chat. */
        public String describe(Season season) {
            if (days == 0) {
                return season.label() + ": no days";
            }
            StringBuilder kinds = new StringBuilder();
            for (Map.Entry<Precip, Double> e : kindHours.entrySet()) {
                if (e.getValue() >= 0.5) {
                    if (!kinds.isEmpty()) {
                        kinds.append(", ");
                    }
                    kinds.append(e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(' ')
                            .append(String.format(Locale.ROOT, "%.0f", e.getValue()));
                }
            }
            return String.format(Locale.ROOT, "%s (%d d): mean %.1f C (lows %.1f, highs %.1f; %.1f to %.1f; day to day "
                            + "±%.1f), frost %d d; wet %d d, %.0f h%s; thunder heard %.0f h (overhead %.0f h, storm nearby "
                            + "%d h, hail-capable %d h); wind %.1f m/s, gales %d h; longest dry %d d",
                    season.label(), days, mean, lowMean, highMean, coldest, warmest, variability, frostDays, wetDays,
                    wetHours, kinds.isEmpty() ? "" : " (" + kinds + ")", heardHours, thunderHours, nearHours, hailHours,
                    windMean, galeHours, longestDry);
        }
    }

    private final List<Day> days = new ArrayList<>();
    private Day current;

    /**
     * Folds a spot's readings ({@link ForecastRun#observeSpot}) into one hour, each corrected by the spot's
     * temperature offset {@code offset} (C), with the surface wind (wx, wz), m/s.
     */
    public static Hour hourOf(ForecastChance.Point[] points, double offset, double wx, double wz, long day,
                              double yearFraction) {
        double t = 0;
        double wet = 0;
        boolean hail = false;
        boolean near = false;
        double thunder = 0;
        EnumMap<Precip, Double> kinds = new EnumMap<>(Precip.class);
        for (ForecastChance.Point raw : points) {
            ForecastChance.Point p = raw.shifted(offset);
            t += p.groundT();
            double w = p.wet();
            wet += w;
            if (w > 0 && p.kind() != null) {
                kinds.merge(p.kind(), w, Double::sum);
            }
            if (p.thunder() && p.heapWet() > 0) {
                near = true;
                thunder += p.heapWet();
                if (p.hail()) {
                    hail = true;
                }
            }
        }
        int n = Math.max(1, points.length);
        Precip kind = null;
        double best = 0;
        for (Map.Entry<Precip, Double> e : kinds.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                kind = e.getKey();
            }
        }
        return new Hour(day, yearFraction, t / n, wet / n, kind, hail, near, Math.hypot(wx, wz), thunder / n);
    }

    /** Adds an hour's reading (in time order). */
    public void add(Hour h) {
        if (current == null || current.day != h.day()) {
            current = new Day(h.day(), h.yearFraction());
            days.add(current);
        }
        current.add(h);
    }

    /** Every day so far, oldest first. */
    public List<Day> days() {
        return days;
    }

    /** Hours read so far. */
    public int hours() {
        int n = 0;
        for (Day d : days) {
            n += d.hours;
        }
        return n;
    }

    /** The summary of {@code season} over the days so far. */
    public Summary summary(Season season) {
        int n = 0;
        double tSum = 0;
        int hours = 0;
        double lowSum = 0;
        double highSum = 0;
        double coldest = Double.POSITIVE_INFINITY;
        double warmest = Double.NEGATIVE_INFINITY;
        int frost = 0;
        int wet = 0;
        double wetHours = 0;
        EnumMap<Precip, Double> kinds = new EnumMap<>(Precip.class);
        int hail = 0;
        double thunder = 0;
        double heard = 0;
        int near = 0;
        double windSum = 0;
        int gales = 0;
        int dry = 0;
        int longestDry = 0;
        double prevMean = Double.NaN;
        double dSum = 0;
        double dSq = 0;
        int dN = 0;
        for (Day d : days) {
            if (d.season != season || d.hours == 0) {
                prevMean = Double.NaN;
                continue;
            }
            n++;
            if (d.hours >= 20) {
                if (Double.isFinite(prevMean)) {
                    double change = d.mean() - prevMean;
                    dSum += change;
                    dSq += change * change;
                    dN++;
                }
                prevMean = d.mean();
            } else {
                prevMean = Double.NaN;
            }
            tSum += d.tSum;
            hours += d.hours;
            lowSum += d.low;
            highSum += d.high;
            coldest = Math.min(coldest, d.low);
            warmest = Math.max(warmest, d.high);
            if (d.frost()) {
                frost++;
            }
            if (d.wet()) {
                wet++;
                dry = 0;
            } else {
                dry++;
                longestDry = Math.max(longestDry, dry);
            }
            wetHours += d.wetHours;
            for (Map.Entry<Precip, Double> e : d.kinds.entrySet()) {
                kinds.merge(e.getKey(), e.getValue(), Double::sum);
            }
            hail += d.hailHours;
            thunder += d.thunderHours;
            heard += d.heardHours;
            near += d.nearHours;
            windSum += d.windSum;
            gales += d.galeHours;
        }
        if (n == 0) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, Map.of(), 0, 0,
                    Double.NaN, 0, 0, Double.NaN, 0, 0);
        }
        double variability = dN < 2 ? Double.NaN : Math.sqrt(Math.max(0, dSq / dN - (dSum / dN) * (dSum / dN)));
        return new Summary(n, tSum / hours, lowSum / n, highSum / n, coldest, warmest, frost, wet, wetHours,
                java.util.Collections.unmodifiableMap(kinds), hail, thunder, windSum / hours, gales, longestDry,
                variability, near, heard);
    }

    /**
     * Wet and thunder-heard hours of each sub-season of {@code season} (early, mid, late; Serene Seasons' 12
     * sub-seasons by where each day starts in the year): one line, e.g.
     * {@code "early 22 h / heard 3 h (6 d); mid ...; late ..."}.
     * (8b follow-up, 2026-10-14: to see where in a season the rain falls, such as a dry season's wet start.)
     */
    public String describeSubSeasons(Season season) {
        String[] names = {"early", "mid", "late"};
        double[] wet = new double[3];
        double[] thunder = new double[3];
        int[] n = new int[3];
        for (Day d : days) {
            if (d.season != season || d.hours == 0) {
                continue;
            }
            double y = d.yearFraction - Math.floor(d.yearFraction);
            int sub = Math.floorMod((int) Math.floor(y * 12), 12) % 3;
            wet[sub] += d.wetHours;
            thunder[sub] += d.heardHours;
            n[sub]++;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 3; k++) {
            if (k > 0) {
                sb.append("; ");
            }
            sb.append(String.format(Locale.ROOT, "%s %.0f h / heard %.0f h (%d d)", names[k], wet[k], thunder[k],
                    n[k]));
        }
        return sb.toString();
    }

    /** The index of the first day of the warmest ({@code warmest}) or coldest 6-day stretch, or -1 if too short. */
    int extremeWeek(boolean warmest) {
        int best = -1;
        double bestMean = warmest ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i = 0; i + WEEK_DAYS <= days.size(); i++) {
            double sum = 0;
            for (int j = i; j < i + WEEK_DAYS; j++) {
                sum += days.get(j).mean();
            }
            double m = sum / WEEK_DAYS;
            if (warmest ? m > bestMean : m < bestMean) {
                bestMean = m;
                best = i;
            }
        }
        return best;
    }

    /** Where in the year the middle of the warmest (or coldest) week falls, 0-1, or NaN. */
    public double extremeWeekYear(boolean warmest) {
        int i = extremeWeek(warmest);
        if (i < 0) {
            return Double.NaN;
        }
        double y = days.get(i + WEEK_DAYS / 2).yearFraction;
        return y - Math.floor(y);
    }

    /**
     * Where in the year the first snowy day after the warmest week ({@code first}) or the last one before it falls, 0-1,
     * or NaN if there is none. The run is taken as one cycle, so the search wraps around its ends (a run started in
     * spring finds the winter's last snow at its end).
     */
    public double snowYear(boolean first) {
        int w = extremeWeek(true);
        if (w < 0) {
            return Double.NaN;
        }
        int n = days.size();
        for (int k = 0; k < n; k++) {
            int i = first ? (w + k) % n : Math.floorMod(w - 1 - k, n);
            if (days.get(i).snowHours() >= SNOW_DAY_HOURS) {
                return days.get(i).yearFraction - Math.floor(days.get(i).yearFraction);
            }
        }
        return Double.NaN;
    }

    /** One line on the year's shape: warmest and coldest weeks, first and last snow. */
    public String describeYear() {
        java.util.function.DoubleFunction<String> pct = v -> Double.isFinite(v)
                ? String.format(Locale.ROOT, "%.0f%% (%s)", 100 * v, Season.of(v).label().toLowerCase(Locale.ROOT))
                : "none";
        return "warmest week " + pct.apply(extremeWeekYear(true)) + ", coldest week " + pct.apply(extremeWeekYear(false))
                + "; first snow " + pct.apply(snowYear(true)) + ", last snow " + pct.apply(snowYear(false));
    }
}
