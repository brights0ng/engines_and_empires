package dev.brights0ng.enginesandempires.weather.climate;

/**
 * The sun's warmth on a person, the maths (weather phase 8e-2, {@code claude/weather-backbone-phase8e.md}; Bright,
 * 2026-10-15). Pure Java.
 *
 * <p>Shade is only the absence of sun, so there is no shade term: a person gets {@code max × height × through}, where
 * <ul>
 *   <li>{@code max}: the warmth of full sun at noon under a clear sky, C (server config, 8);</li>
 *   <li>{@code height}: how high the sun is (the vertical part of its direction): 1 at noon, 0 at the horizon and by night;</li>
 *   <li>{@code through}: the share of the sunlight that reaches them, the <b>lower</b> of what the blocks and what the
 *       clouds let through (the stronger obscurer wins; they do not multiply). Blocks: 1 for clear air, a quarter for
 *       anything that lets some light through (glass, leaves, slabs, water, in any number), 0 for a block that stops
 *       light. Clouds: {@code exp(-depth)} of the optical depth along the ray ({@code StormShade}).</li>
 * </ul>
 * The sun is vanilla's: an east-west arc, overhead at noon, no seasonal tilt.
 */
public final class SunLight {

    /** Share of the sun let through by blocks that let some light through. */
    public static final double TRANSPARENT = 0.25;

    /** What a block does to the ray: nothing, partly (the {@link #TRANSPARENT} share gets through), or stops it. */
    public static final int PASS = 0;
    public static final int PARTLY = 1;
    public static final int STOPS = 2;

    /**
     * The unit vector toward the sun at vanilla's time of day {@code timeOfDay} (0-1, 0 = noon, as
     * {@code Level.getTimeOfDay}): x east, y up, z 0. It rises in the east (+x) and sets in the west.
     */
    public static double[] direction(double timeOfDay) {
        double angle = timeOfDay * Math.PI * 2;
        return new double[] {-Math.sin(angle), Math.cos(angle), 0};
    }

    /** How high the sun is, 0-1: 0 on or under the horizon. */
    public static double height(double dy) {
        return Math.max(0, Math.min(1, dy));
    }

    /** The share of the sun blocks let through for the worst block class met (see {@link #PASS}). */
    public static double blockShare(int worst) {
        return worst >= STOPS ? 0 : worst == PARTLY ? TRANSPARENT : 1;
    }

    /** The share of the sun clouds let through for an optical depth. */
    public static double cloudShare(double opticalDepth) {
        return Math.exp(-Math.max(0, opticalDepth));
    }

    /** The share of the sun that gets through: the lower of the two. */
    public static double through(double blockShare, double cloudShare) {
        return Math.min(blockShare, cloudShare);
    }

    /** The warmth, C: {@code max × height × through}. */
    public static double warmth(double max, double height, double through) {
        return Math.max(0, max) * height * through;
    }

    private SunLight() {
    }
}
