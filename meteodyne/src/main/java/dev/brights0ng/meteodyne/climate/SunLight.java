// Edited by Claude: the sun's strength through the year (obliquity and latitude; Bright, 2026-11-15).
package dev.brights0ng.meteodyne.climate;

/**
 * The sun's warmth on a person, the maths (weather phase 8e-2, {@code claude/weather-backbone-phase8e.md}; Bright,
 * 2026-10-15). Pure Java.
 *
 * <p>Shade is only the absence of sun, so there is no shade term: a person gets {@code max × height × through}, where
 * <ul>
 *   <li>{@code max}: the warmth of full sun at noon under a clear sky, C (server config, 8 at midsummer), times the
 *       season's strength ({@link #season}: half at midwinter with the defaults);</li>
 *   <li>{@code height}: how high the sun is (the vertical part of its direction): 1 at noon, 0 at the horizon and by night;</li>
 *   <li>{@code through}: the share of the sunlight that reaches them, the <b>lower</b> of what the blocks and what the
 *       clouds let through (the stronger obscurer wins; they do not multiply). Blocks: 1 for clear air, a quarter for
 *       anything that lets some light through (glass, leaves, slabs, water, in any number), 0 for a block that stops
 *       light. Clouds: {@code exp(-depth)} of the optical depth along the ray ({@code StormShade}).</li>
 * </ul>
 * The sun's path is vanilla's: an east-west arc, overhead at noon, no seasonal tilt. The seasons come in only through
 * {@link #season}, from the world's obliquity and latitude (config), which a tilted sky will follow later.
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

    /**
     * The sun's strength at noon through the year, 0-1, as a share of the year's strongest (Bright, 2026-11-15: with
     * Earth's tilt, midwinter's is half of midsummer's). The sun's declination is
     * {@code asin(sin(obliquity) × sun)}; its noon height at {@code latitude} is {@code 90° − |latitude − declination|},
     * and the strength is that height's sine, {@code cos(latitude − declination)}, over the year's highest.
     *
     * @param obliquityDeg the world's axial tilt, degrees (0-90)
     * @param latitudeDeg  how far from the equator the world sits, degrees (0-90)
     * @param sun          {@link ClimateCurves#sun}: +1 at the start of summer, -1 at the start of winter, 0 at the
     *                     equinoxes and without seasons (NaN counts as 0)
     */
    public static double season(double obliquityDeg, double latitudeDeg, double sun) {
        double e = Math.toRadians(clamp(obliquityDeg, 0, 90));
        double phi = Math.toRadians(clamp(latitudeDeg, 0, 90));
        double s = Double.isFinite(sun) ? clamp(sun, -1, 1) : 0;
        double declination = Math.asin(Math.sin(e) * s);
        // The year's highest noon sun: overhead if the sun reaches the latitude, else at the summer solstice
        double peak = phi <= e ? 1 : Math.cos(phi - e);
        if (peak < 1e-9) {
            return 0; // the pole with no tilt: the sun never rises above the horizon
        }
        return Math.max(0, Math.cos(phi - declination)) / peak;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }

    private SunLight() {
    }
}
