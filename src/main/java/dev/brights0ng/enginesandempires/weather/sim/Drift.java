package dev.brights0ng.enginesandempires.weather.sim;

/**
 * Each weather system's slow random drift (phase 7a of {@code claude/weather-backbone-phase7.md}): what makes the live
 * weather wander off the forecast. Pure.
 *
 * <h2>Why</h2>
 * The earlier nudges were fresh random numbers every step, so they cancelled out (a low ended up 10-20 blocks off an
 * un-nudged run after 5 days). A forecast was then nearly perfect at any range.
 *
 * <h2>How</h2>
 * Each system carries three drift states (speed along its track, drift across it, depth), each an Ornstein-Uhlenbeck
 * process in units of its own standard deviation: it wanders, and relaxes back toward zero over
 * {@link Settings#correlationTicks} (about an in-game day). The update is the exact one for any step length, so a
 * 5 s live step, a 50 s {@code /eae weather step} chunk and an in-game-hour forecast step all agree statistically.
 * <ul>
 *   <li><b>Live runs</b> step the states with random numbers ({@link #step}).</li>
 *   <li><b>Forecast runs</b> don't know the future randomness, so they step each state to its expected value: it just
 *       relaxes toward zero ({@link #keep}). A forecast therefore knows how a system is moving now (persistence) and
 *       gets less sure of it the further ahead it looks.</li>
 * </ul>
 * With the defaults (speed 10%, a day's memory) the timing of a front drifts by roughly 2 in-game hours by day 1 and
 * 5-6 hours by day 4 (checked by {@code DriftTest}).
 */
public final class Drift {

    /**
     * How much systems drift.
     *
     * @param speed            standard deviation of the speed along the track, as a share of it
     * @param cross            standard deviation of the drift across the track, as a share of the speed
     * @param depth            standard deviation of the depth (strength), as a share of it
     * @param correlationTicks how long a drift lasts before it has mostly relaxed away, ticks
     */
    public record Settings(double speed, double cross, double depth, long correlationTicks) {

        public static final Settings DEFAULT = new Settings(0.10, 0.05, 0.15, 24_000);
        /** No drift at all (tests: the forecast engine itself must add no error). */
        public static final Settings NONE = new Settings(0, 0, 0, 24_000);
    }

    /** The depth factor is kept inside this range, so a system never vanishes or doubles from drift alone. */
    static final double DEPTH_MIN = 0.6;
    static final double DEPTH_MAX = 1.4;
    /** The speed factor never falls below this (a system never stalls or runs backwards from drift alone). */
    static final double SPEED_MIN = 0.3;

    /** The share of a drift state that remains after {@code dt} ticks with nothing new added. */
    public static double keep(long dt, long correlationTicks) {
        if (correlationTicks <= 0) {
            return 0;
        }
        return Math.exp(-(double) Math.max(0, dt) / correlationTicks);
    }

    /** One live step of a unit-variance drift state {@code u}, with share {@code keep} kept and a fresh normal number. */
    public static double step(double u, double keep, double gaussian) {
        return u * keep + Math.sqrt(Math.max(0, 1 - keep * keep)) * gaussian;
    }

    /** The speed factor for drift state {@code u}. */
    public static double speedFactor(double u, double sigma) {
        return Math.max(SPEED_MIN, 1 + sigma * u);
    }

    /** The depth factor for drift state {@code u}. */
    public static double depthFactor(double u, double sigma) {
        return Math.max(DEPTH_MIN, Math.min(DEPTH_MAX, 1 + sigma * u));
    }

    /** The drift state that gives depth factor {@code factor} (loading worlds saved before drift existed). */
    public static double depthStateFor(double factor, double sigma) {
        return sigma <= 0 ? 0 : (factor - 1) / sigma;
    }

    private Drift() {
    }
}
