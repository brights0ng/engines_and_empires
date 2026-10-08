package dev.brights0ng.enginesandempires.weather.cloud.client;

/**
 * The colours of the light on the clouds through the day (2026-10-07, Bright: realistic sunrise and sunset, and a
 * short afterglow): pure, so it can be tested.
 *
 * <p>A cloud's vertices carry two amounts of light ({@link CloudVoxelizer}): the sky's (ambient) light and the sun's (or
 * moon's) direct light. The shader colours them with {@link #sky} and {@link #sun}:
 * <ul>
 *   <li><b>Day:</b> the sunlight a little warm and the sky's light a little cool (Bright, 2026-10-07: sun-facing sides
 *       slightly yellow, shaded sides slightly blue); together, about white.</li>
 *   <li><b>Low sun:</b> the direct light turns gold, then orange, then red-orange at the horizon, and grows stronger
 *       against the sky's light (the sky dims and turns blue-violet), so lit sides glow warm and shadow sides go cool.</li>
 *   <li><b>Afterglow:</b> for a short while after sunset (and before sunrise), the sun, just below the horizon, still
 *       lights the clouds from underneath, pink-red and fading, while their tops go grey.</li>
 *   <li><b>Night:</b> a dim blue sky light and pale blue moonlight.</li>
 * </ul>
 * All by the sun's height {@code h}: the sine of its elevation (1 overhead, 0 on the horizon).
 */
public final class CloudColours {

    /** The sun's height at which the afterglow is over (about 6 degrees below the horizon). */
    public static final double AFTERGLOW_END = -0.1;

    /** Sun heights and the direct light's colour at each (times its strength against the sky's light). */
    private static final double[] SUN_H = {AFTERGLOW_END, -0.05, 0.0, 0.06, 0.17, 0.35};
    private static final double[][] SUN_C = {
            {0.90, 0.35, 0.38},
            {2.40, 1.00, 0.90},
            {1.85, 0.95, 0.55},
            {1.75, 1.20, 0.70},
            {1.25, 1.07, 0.66},
            {1.11, 1.01, 0.69}};

    /** Sun heights and the sky light's colour at each. */
    private static final double[] SKY_H = {-0.3, -0.1, 0.0, 0.1, 0.3};
    private static final double[][] SKY_C = {
            {0.10, 0.11, 0.16},
            {0.26, 0.26, 0.38},
            {0.45, 0.43, 0.58},
            {0.85, 0.84, 0.92},
            {0.94, 0.97, 1.06}};

    /** Moonlight's colour (its strength is in the light, {@link CloudLight#MOON}). */
    private static final double[] MOON = {0.30, 0.33, 0.45};

    /** The direct light's colour with the sun at height {@code h} (the moon's once the sun is well down). */
    public static double[] sun(double h) {
        if (h <= AFTERGLOW_END) {
            return MOON.clone();
        }
        return lerp(SUN_H, SUN_C, h);
    }

    /** The sky light's colour with the sun at height {@code h}. */
    public static double[] sky(double h) {
        return lerp(SKY_H, SKY_C, h);
    }

    /** The sun's height at vanilla's time of day {@code timeOfDay} (0 = noon). */
    public static double sunHeight(double timeOfDay) {
        return Math.cos(timeOfDay * Math.PI * 2);
    }

    private static double[] lerp(double[] xs, double[][] cs, double x) {
        if (x <= xs[0]) {
            return cs[0].clone();
        }
        for (int k = 1; k < xs.length; k++) {
            if (x <= xs[k]) {
                double t = (x - xs[k - 1]) / (xs[k] - xs[k - 1]);
                t = t * t * (3 - 2 * t);
                double[] a = cs[k - 1], b = cs[k];
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
            }
        }
        return cs[cs.length - 1].clone();
    }

    private CloudColours() {
    }
}
