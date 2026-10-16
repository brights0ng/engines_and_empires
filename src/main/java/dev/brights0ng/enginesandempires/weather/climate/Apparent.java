package dev.brights0ng.enginesandempires.weather.climate;

/**
 * How the air feels, beyond its temperature (weather phase 8e-2, {@code claude/weather-backbone-phase8e.md}; Bright,
 * 2026-10-15). Pure Java.
 *
 * <ul>
 *   <li><b>Wind chill:</b> the standard JAG/TI formula (Environment Canada and the US NWS), eased in so nothing jumps:
 *       from 10 C (none) to 5 C (all of it) and from 1.3 m/s (none) to 3 m/s (all of it).</li>
 *   <li><b>Heat index:</b> the NWS's Rothfusz regression with its two adjustments (and its simple formula below 80 F),
 *       eased in between 24 and 28 C so nothing jumps.</li>
 *   <li><b>Scale and cap</b> (server config): each offset is multiplied by {@link Settings#scale}, and, if the cap is on,
 *       limited to {@link Settings#cap} C.</li>
 * </ul>
 * Speeds are m/s (a block is a metre for velocities, as the ship wind has it), temperatures C, humidity 0-1.
 */
public final class Apparent {

    /** Wind chill is none at or above this air temperature, C, and all of it at or below {@link #CHILL_FULL_C}. */
    public static final double CHILL_NONE_C = 10;
    public static final double CHILL_FULL_C = 5;
    /** Wind chill is none at or below this wind, m/s (the formula starts at 4.8 km/h), and all of it at {@link #CHILL_FULL_MPS}. */
    public static final double CHILL_NONE_MPS = 1.3;
    public static final double CHILL_FULL_MPS = 3;
    /** Heat index is none at or below this air temperature, C, and all of it at or above {@link #HEAT_FULL_C}. */
    public static final double HEAT_NONE_C = 24;
    public static final double HEAT_FULL_C = 28;

    /** The server config's say: a multiplier, and an optional cap (C) on each offset. */
    public record Settings(double scale, boolean capEnabled, double cap) {

        public static final Settings DEFAULT = new Settings(1.0, false, 0);

        /** {@code offset} (C, 0 or more) after the scale and cap. */
        public double apply(double offset) {
            double o = Math.max(0, offset) * Math.max(0, scale);
            return capEnabled ? Math.min(o, Math.max(0, cap)) : o;
        }
    }

    /** The speed of the wind a person feels, m/s: the wind minus their own motion, horizontally. */
    public static double relativeWind(double windX, double windZ, double velocityX, double velocityZ) {
        return Math.hypot(windX - velocityX, windZ - velocityZ);
    }

    /**
     * How much colder than the air it feels, C (0 or more), for air at {@code airC} and a relative wind of
     * {@code windMps}.
     */
    public static double windChillDrop(double airC, double windMps) {
        double weight = smooth(CHILL_NONE_C, CHILL_FULL_C, airC) * smooth(CHILL_NONE_MPS, CHILL_FULL_MPS, windMps);
        if (weight <= 0) {
            return 0;
        }
        double kmh = Math.max(4.8, windMps * 3.6);
        double v = Math.pow(kmh, 0.16);
        double chill = 13.12 + 0.6215 * airC - 11.37 * v + 0.3965 * airC * v;
        return Math.max(0, airC - chill) * weight;
    }

    /** How much hotter than the air it feels, C (0 or more), for air at {@code airC} and relative humidity {@code rh} 0-1. */
    public static double heatIndexRise(double airC, double rh) {
        double weight = smooth(HEAT_NONE_C, HEAT_FULL_C, airC);
        if (weight <= 0) {
            return 0;
        }
        double t = airC * 9 / 5 + 32;
        double h = Math.max(0, Math.min(1, rh)) * 100;
        double index = (0.5 * (t + 61.0 + (t - 68.0) * 1.2 + h * 0.094) + t) / 2;
        if (index >= 80) {
            index = -42.379 + 2.04901523 * t + 10.14333127 * h - 0.22475541 * t * h - 6.83783e-3 * t * t
                    - 5.481717e-2 * h * h + 1.22874e-3 * t * t * h + 8.5282e-4 * t * h * h - 1.99e-6 * t * t * h * h;
            if (h < 13 && t >= 80 && t <= 112) {
                index -= (13 - h) / 4 * Math.sqrt((17 - Math.abs(t - 95)) / 17);
            } else if (h > 85 && t >= 80 && t <= 87) {
                index += (h - 85) / 10 * (87 - t) / 5;
            }
        } else {
            index = 0.5 * (t + 61.0 + (t - 68.0) * 1.2 + h * 0.094);
        }
        return Math.max(0, (index - t) * 5 / 9) * weight;
    }

    /** 0 at {@code from}, 1 at {@code to} (either way round), a smooth step between. */
    static double smooth(double from, double to, double x) {
        double t = (x - from) / (to - from);
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private Apparent() {
    }
}
