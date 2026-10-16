package dev.brights0ng.enginesandempires.weather.climate;

/**
 * The shapes of the climate's cycles, pure maths (Bright, 2026-10-05: realistic seasons, realistic temperatures).
 *
 * <ul>
 *   <li><b>Seasons, with a lag</b> (weather phase 8b, 2026-10-14). The year is Serene Seasons' cycle, starting at early
 *       spring; one sub-season is 1/12. Three cosines, -1 at their winter low and +1 at their summer high:
 *       <ul>
 *         <li>{@link #sun}: the sun, highest at the start of summer (1/4, the solstice), lowest at the start of winter.
 *             Drives the day's heating and convection.</li>
 *         <li>{@link #season}: the air over land, about one sub-season behind the sun (warmest at 1/3, coldest 5/6),
 *             as real inland temperatures lag the sun by about a month. Also drives the weather systems.</li>
 *         <li>{@link #sea}: the sea and the air over it, about two sub-seasons behind the sun (5/12, 11/12).</li>
 *       </ul>
 *       While the sun leads the air (spring) the ground heats air still cold aloft: unstable, showery weather; while it
 *       trails (autumn) the air is settled.</li>
 *   <li><b>Tropical wet and dry seasons</b> ({@link #tropicalWet}): +1 at the height of the wet season, -1 of the dry,
 *       following Serene Seasons' tropical calendar (wet early winter to late spring, mid wet around the turn of the
 *       year; dry early summer to late autumn).</li>
 *   <li><b>Day and night</b> ({@link #diurnal}): coldest at dawn (6:00, Minecraft's day time 0), warmest mid-afternoon
 *       (15:00), warming over 9 hours and cooling over 15, as real days do. Its size comes from {@link #diurnalRange}:
 *       dry air swings far more than humid air (deserts about 18 C from dawn to afternoon, rainforests about 6 C).</li>
 *   <li><b>Climate bands</b> ({@link #band}): the large-scale gradient along Z in {@code bands} mode, repeating every
 *       {@code period} blocks: 0 at z = 0 (spawn sits in the temperate middle), coldest a quarter period to the north
 *       (negative Z, as on the compass) and warmest a quarter period to the south.</li>
 * </ul>
 */
public final class ClimateCurves {

    /** Where the sun is highest in Serene Seasons' year (0 = start of early spring): the start of summer. */
    public static final double SUN_PEAK = 0.25;
    /** Where the air over land is warmest: one sub-season after the sun. */
    public static final double MIDSUMMER = 1.0 / 3;
    /** Where the sea is warmest: two sub-seasons after the sun. */
    public static final double SEA_PEAK = 5.0 / 12;
    /** The height of the tropical wet season (Serene Seasons' mid wet straddles the turn of the year). */
    public static final double TROPICAL_WET_PEAK = 0.0;
    /** How much stronger the climate bands are in midwinter (and weaker in midsummer). */
    public static final double BAND_SEASONAL = 0.3;

    /**
     * The season factor of the air over land at {@code yearFraction} (0-1, wraps): -1 midwinter to +1 midsummer. NaN
     * gives 0 (no seasons).
     */
    public static double season(double yearFraction) {
        return cycle(yearFraction, MIDSUMMER);
    }

    /** The sun's strength through the year, -1 at the start of winter to +1 at the start of summer (NaN gives 0). */
    public static double sun(double yearFraction) {
        return cycle(yearFraction, SUN_PEAK);
    }

    /** The sea's season factor, two sub-seasons behind the sun (NaN gives 0). */
    public static double sea(double yearFraction) {
        return cycle(yearFraction, SEA_PEAK);
    }

    /** The tropical wet season, +1 at its height to -1 at the height of the dry season (NaN gives 0). */
    public static double tropicalWet(double yearFraction) {
        return cycle(yearFraction, TROPICAL_WET_PEAK);
    }

    /** How strongly the climate bands apply at land season factor {@code season}: 1.3 midwinter, 0.7 midsummer. */
    public static double bandScale(double season) {
        double s = Double.isFinite(season) ? Math.max(-1, Math.min(1, season)) : 0;
        return 1 - BAND_SEASONAL * s;
    }

    private static double cycle(double yearFraction, double peak) {
        if (!Double.isFinite(yearFraction)) {
            return 0;
        }
        return Math.cos(2 * Math.PI * (yearFraction - peak));
    }

    /**
     * The day-night factor at Minecraft day time {@code dayTime} (ticks; 0 = 6:00, 6000 = noon): -1 at dawn, +1 at
     * 15:00.
     */
    public static double diurnal(long dayTime) {
        double hour = (6 + Math.floorMod(dayTime, 24000L) / 1000.0) % 24;
        if (hour >= 6 && hour < 15) {
            return -Math.cos(Math.PI * (hour - 6) / 9);
        }
        double since = hour >= 15 ? hour - 15 : hour + 9;
        return Math.cos(Math.PI * since / 15);
    }

    /** First hour the sun heats the ground enough to drive convection. */
    public static final double SUN_START = 8;
    /** Hour convective heating ends (the sun sets at 18:00, Minecraft day time 12000). */
    public static final double SUN_END = 18.5;

    /**
     * How hard the sun is heating the ground at Minecraft day time {@code dayTime}, 0-1: 0 from {@value #SUN_END} to
     * {@value #SUN_START}, peaking about 13:15. Unlike {@link #diurnal} (the air temperature, which lags and stays above
     * the day's mean until 22:30) this is what drives convection: fair-weather cumulus build from mid-morning and die
     * off by dusk.
     */
    public static double solar(long dayTime) {
        double hour = (6 + Math.floorMod(dayTime, 24000L) / 1000.0) % 24;
        if (hour <= SUN_START || hour >= SUN_END) {
            return 0;
        }
        return Math.sin(Math.PI * (hour - SUN_START) / (SUN_END - SUN_START));
    }

    /** How much of its daytime buoyancy the air over land keeps at full night, under the night's inversion. */
    public static final double NIGHT_BUOYANCY = 0.3;

    /**
     * How much of the air's buoyancy over land survives at {@code dayTime}, {@value #NIGHT_BUOYANCY}-1: all of it by
     * day; after {@value #SUN_END} the ground cools under a clear sky and an inversion caps surface-based convection,
     * fading to {@value #NIGHT_BUOYANCY} over three hours; the morning sun breaks it from {@value #SUN_START} to 10:00.
     * (2026-10-06: cold air aloft kept land cumulus fields going all night.)
     */
    public static double landBuoyancy(long dayTime) {
        double hour = (6 + Math.floorMod(dayTime, 24000L) / 1000.0) % 24;
        double calm;
        if (hour >= SUN_END) {
            calm = smooth((hour - SUN_END) / 3);
        } else if (hour < SUN_START) {
            calm = 1;
        } else {
            calm = 1 - smooth((hour - SUN_START) / 2);
        }
        return 1 - (1 - NIGHT_BUOYANCY) * calm;
    }

    private static double smooth(double t) {
        double c = t < 0 ? 0 : Math.min(1, t);
        return c * c * (3 - 2 * c);
    }

    /** The day-night half-range for air of {@code humidity} (0-1), C. */
    public static double diurnalRange(double humidity) {
        double h = Math.max(0, Math.min(1, humidity));
        return 9 - 6 * h;
    }

    /** The band offset at {@code z} for bands {@code period} blocks long and {@code amplitude} C strong. */
    public static double band(double z, double period, double amplitude) {
        if (period <= 0) {
            return 0;
        }
        return amplitude * Math.sin(2 * Math.PI * z / period);
    }

    private ClimateCurves() {
    }
}
