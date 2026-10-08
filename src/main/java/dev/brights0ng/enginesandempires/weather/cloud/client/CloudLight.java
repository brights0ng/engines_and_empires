package dev.brights0ng.enginesandempires.weather.cloud.client;

/**
 * Where the clouds' light comes from (2026-10-07, Bright: realistic contrast, moonlight at night): the sun by day, the
 * moon by night, as a unit vector toward it and a strength. Vanilla's sky: the sun rises in the east (+x), passes
 * overhead and sets in the west; the moon is opposite. The sun keeps its full strength down to just above the horizon
 * (sunset light is strong, and coloured by {@link CloudColours}), and goes on lighting the clouds from below for a
 * short afterglow once it has set, fading out by {@link CloudColours#AFTERGLOW_END}; then the moon fades in. The
 * light never jumps from one to the other.
 *
 * @param x        toward the light, unit vector
 * @param strength 1 for a high sun, {@link #MOON} for a high moon, 0 at the horizon
 */
public record CloudLight(double x, double y, double z, double strength) {

    /** The moon's strength, as a share of the sun's. */
    public static final double MOON = 0.6;

    /** The light at vanilla's time of day {@code timeOfDay} (0-1, 0 = noon, as {@code Level.getTimeOfDay}). */
    public static CloudLight at(double timeOfDay) {
        double a = timeOfDay * Math.PI * 2;
        double sx = -Math.sin(a);
        double sy = Math.cos(a);
        // A little toward +z, so the light isn't exactly along x at the horizon and lights some faces from the side.
        double sz = 0.18;
        double end = CloudColours.AFTERGLOW_END;
        if (sy >= end) {
            // Full down to the horizon, then fading through the afterglow: the sun below the horizon lights the
            // clouds from underneath. (Its angle below the horizon is exaggerated 5x, so the undersides catch it.)
            return of(sx, sy >= 0 ? sy : sy * 5, sz, smooth((sy - end) / -end));
        }
        return of(-sx, -sy, -sz, MOON * smooth((end - sy) / 0.15));
    }

    /** The light's strength with the sun at height {@code sy} (sine of its elevation), as {@link #at} has it. */
    public static double strengthForHeight(double sy) {
        double end = CloudColours.AFTERGLOW_END;
        return sy >= end ? smooth((sy - end) / -end) : MOON * smooth((end - sy) / 0.15);
    }

    /**
     * The light a build's shadowing through the cloud is worked out for (2026-10-07 evening, Bright: no steps at dusk
     * and dawn; the shader turns the lit side live, see clouds.fsh): the light itself while it is above the horizon.
     * While the sun is just below it (the afterglow, the dawn glow), the light comes from below and the shader takes
     * nothing to be in its way; meanwhile builds are done for the next light from above, so they are ready when it
     * takes over: by dawn the sun about to rise, by dusk the moon.
     */
    public static CloudLight bakeAt(double timeOfDay) {
        CloudLight l = at(timeOfDay);
        if (l.y >= 0) {
            return l;
        }
        double a = timeOfDay * Math.PI * 2;
        double sx = -Math.sin(a);
        double sy = Math.cos(a);
        if (sx > 0) {
            // Dawn: the sun is in the east, rising.
            return of(sx, 0.05, 0.18, l.strength);
        }
        return of(-sx, -sy, -0.18, l.strength);
    }

    private static CloudLight of(double x, double y, double z, double strength) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new CloudLight(x / l, y / l, z / l, strength);
    }

    private static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }
}
