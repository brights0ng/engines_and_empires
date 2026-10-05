package dev.brights0ng.enginesandempires.weather.wind;

/**
 * The force law (Bright, 2026-10-01): quadratic, and wind only ever pushes, never brakes.
 *
 * <p>"Closing speed" is how much faster the wind moves than the object, measured along the wind. The push grows with its
 * square, so light winds barely register and storms hit hard. An object already moving with the wind (or faster) feels
 * nothing.
 *
 * <p>The object's own speed never adds to the push: flying into the wind counts the same as hovering in it. Otherwise a
 * fast ship in a light headwind would feel strong drag from its own motion, and air resistance is left to Sable and
 * Aeronautics. Calm air does nothing at all.
 */
public final class WindForce {

    /** The most of the remaining closing speed one physics step may close, so light objects never overshoot the wind. */
    public static final double MAX_STEP_SHARE = 0.5;

    /**
     * The push, in Sable force units.
     *
     * @param windSpeed         wind speed (m/s)
     * @param velocityAlongWind the object's velocity along the wind's direction (m/s; negative when heading into it,
     *                          which counts as 0)
     * @param area              the object's silhouette across the wind (m²)
     * @param pressure          Sable's air pressure at the object (1 at sea level)
     * @param exposure          how exposed it is, from 0 (fully sheltered) to 1 (open air)
     * @param coefficient       {@link WindParams#pushCoefficient()}
     */
    public static double push(double windSpeed, double velocityAlongWind, double area, double pressure,
                              double exposure, double coefficient) {
        double closing = closing(windSpeed, velocityAlongWind);
        if (closing <= 0 || area <= 0 || pressure <= 0 || exposure <= 0 || coefficient <= 0) {
            return 0;
        }
        return coefficient * pressure * exposure * area * closing * closing;
    }

    /** How much faster the wind moves than the object along it; heading into the wind counts as standing still. */
    public static double closing(double windSpeed, double velocityAlongWind) {
        if (windSpeed <= 0) {
            return 0;
        }
        return windSpeed - Math.max(0, velocityAlongWind);
    }

    /**
     * Caps one step's impulse so it can close at most {@link #MAX_STEP_SHARE} of the gap between the object and the wind.
     * Without it a light object in a storm could be thrown past wind speed in a single step.
     */
    public static double limitImpulse(double impulse, double mass, double closing) {
        if (impulse <= 0 || mass <= 0 || closing <= 0) {
            return 0;
        }
        return Math.min(impulse, MAX_STEP_SHARE * mass * closing);
    }

    private WindForce() {
    }
}
