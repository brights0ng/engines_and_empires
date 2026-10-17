package dev.brights0ng.meteodyne.climate;

import dev.brights0ng.meteodyne.rain.Precip;

/**
 * How fast falling precipitation soaks a person (weather phase 8e-1, {@code claude/weather-backbone-phase8e.md};
 * Bright, 2026-10-15). Pure Java: no Cold Sweat classes, so it can be tested alone.
 *
 * <p>Cold Sweat has one rain soak speed, for rain at full strength. The pack scales it by what falls:
 * <ul>
 *   <li><b>Rain, mixed, freezing rain:</b> the normal speed (Bright's ruling). Drizzle (rain below
 *       {@link #DRIZZLE_BELOW} strength, drizzle being about 0.15 and heavy rain 0.7-1) and sleet: a quarter of it.</li>
 *   <li><b>Hail</b> (my call): half. It comes with a downpour but bounces off and doesn't wet.</li>
 *   <li><b>Snow</b> (my call): an eighth. It settles on clothes and melts slowly; it soaks, but much slower than
 *       sleet.</li>
 * </ul>
 * Wetness is Cold Sweat's: a negative temperature on the {@code WaterTempModifier}, deeper is wetter, and rain soaks
 * only down to its "max rain soak" (water itself goes further).
 */
public final class RainSoak {

    /** Rain below this strength is drizzle. */
    public static final double DRIZZLE_BELOW = 0.25;

    /** Fractions of Cold Sweat's rain soak speed. */
    public static final double FULL = 1.0;
    public static final double DRIZZLE = 0.25;
    public static final double SLEET = 0.25;
    public static final double HAIL = 0.5;
    public static final double SNOW = 0.125;

    /**
     * The fraction of the normal rain soak speed that {@code kind} falling at {@code strength} (0-1) soaks at. Zero when
     * nothing falls (null kind).
     */
    public static double factor(Precip kind, double strength) {
        if (kind == null) {
            return 0;
        }
        return switch (kind) {
            case RAIN -> strength < DRIZZLE_BELOW ? DRIZZLE : FULL;
            case MIXED, FREEZING_RAIN -> FULL;
            case SLEET -> SLEET;
            case HAIL -> HAIL;
            case SNOW -> SNOW;
        };
    }

    /**
     * Wetness after {@code updates} of Cold Sweat's modifier updates (its unit is one update per 5 ticks): the
     * temperature {@code wetness} (negative when wet) pushed down by {@code speed * factor} per update, but never below
     * {@code -maxRainSoak}. Rain never dries: someone already wetter than the cap (from water) is left as they are.
     */
    public static double soak(double wetness, double factor, double speed, double maxRainSoak, double updates) {
        if (!(factor > 0) || !(speed > 0) || !(updates > 0) || wetness <= -maxRainSoak) {
            return wetness;
        }
        return Math.max(-maxRainSoak, wetness - speed * factor * updates);
    }

    private RainSoak() {
    }
}
