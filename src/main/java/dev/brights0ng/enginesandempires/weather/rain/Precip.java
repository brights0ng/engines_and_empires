package dev.brights0ng.enginesandempires.weather.rain;

/**
 * What falls (phase 5a of the weather backbone, 2026-10-07): the kind, decided from the temperature on the way down
 * ({@link #decide}), and how it falls. Pure Java.
 *
 * <ul>
 *   <li><b>Rain:</b> warm at the ground.</li>
 *   <li><b>Mixed:</b> rain and wet snow together, a degree or three above freezing.</li>
 *   <li><b>Snow:</b> cold all the way down (it survives to about +1 C at the ground).</li>
 *   <li><b>Sleet</b> (ice pellets): snow half-melted in a shallow warm layer aloft that refreezes before landing.</li>
 *   <li><b>Freezing rain:</b> snow fully melted in a deep warm layer aloft that lands as supercooled rain on freezing
 *       ground.</li>
 *   <li><b>Hail:</b> from strong thunderstorm cores, in bursts, whatever the ground temperature
 *       ({@link RainModel#hailAt}).</li>
 * </ul>
 */
public enum Precip {
    RAIN(false),
    MIXED(false),
    SNOW(true),
    SLEET(true),
    FREEZING_RAIN(false),
    HAIL(true);

    /** Whether it lands frozen (no splash, it bounces or settles). */
    public final boolean frozen;

    Precip(boolean frozen) {
        this.frozen = frozen;
    }

    /** Fall speeds, m/s: wet snow, ice pellets and hail (capped; real large hail falls faster still). */
    public static final double MIXED_FALL = 2.5;
    public static final double SLEET_FALL = 7;
    public static final double HAIL_FALL = 14;

    /** How fast it falls, m/s, for rain of {@code strength} 0-1 (harder rain, bigger drops, faster). */
    public double fallMps(double strength) {
        return switch (this) {
            case SNOW -> RainModel.SNOW_FALL;
            case MIXED -> MIXED_FALL;
            case SLEET -> SLEET_FALL;
            case HAIL -> HAIL_FALL;
            default -> RainModel.DRIZZLE_FALL
                    + (RainModel.HEAVY_FALL - RainModel.DRIZZLE_FALL) * Math.min(1, Math.max(0, strength));
        };
    }

    /** Below this the ground is cold enough for snow to arrive as snow (wet snow up to it). */
    public static final double SNOW_MAX = 1;
    /** Up to this, rain and snow fall mixed. */
    public static final double MIXED_MAX = 3;
    /** Melt aloft (warm-nose index, {@code WarmNose}) below which snow falls through unchanged. */
    public static final double MELT_SOME = 0.3;
    /** Melt aloft from which the snow has melted completely. */
    public static final double MELT_ALL = 1.5;

    /**
     * What falls on ground at {@code ground} C, through a warm layer aloft with melt index {@code melt} (0 none; about
     * the warm layer's warmth times its depth) above a cold layer {@code coldDepth} (0 shallow, 1 deep) thick.
     * <ul>
     *   <li>Fully melted aloft: freezing rain on freezing ground (sleet if the cold layer below is deep and very cold,
     *       so the drops refreeze on the way down), rain otherwise.</li>
     *   <li>Partly melted: sleet on freezing ground; mixed or rain on warmer ground.</li>
     *   <li>No warm layer: snow up to {@link #SNOW_MAX}, mixed up to {@link #MIXED_MAX}, rain above.</li>
     * </ul>
     */
    public static Precip decide(double ground, double melt, double coldDepth) {
        if (melt >= MELT_ALL) {
            if (ground < 0) {
                return ground < -5 && coldDepth > 0.5 ? SLEET : FREEZING_RAIN;
            }
            return RAIN;
        }
        if (melt >= MELT_SOME) {
            if (ground < 0) {
                return SLEET;
            }
            return ground < MIXED_MAX ? MIXED : RAIN;
        }
        if (ground <= SNOW_MAX) {
            return SNOW;
        }
        return ground <= MIXED_MAX ? MIXED : RAIN;
    }

    /**
     * How it looks in one streak at block column (x, z): mixed falls as rain in some streaks and wet snow in others
     * (half each); everything else as itself.
     */
    public Precip look(int x, int z) {
        if (this != MIXED) {
            return this;
        }
        long h = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 1) == 0 ? RAIN : SNOW;
    }
}
