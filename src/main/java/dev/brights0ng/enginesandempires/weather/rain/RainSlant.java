package dev.brights0ng.enginesandempires.weather.rain;

/**
 * How far rain and snow lean in the wind, and how that has changed over the last minute, for drawing them (Bright,
 * 2026-10-04: whole rain shafts swung with every gust).
 *
 * <ul>
 *   <li><b>Steady:</b> the lean follows the surface wind averaged over {@link #averageSeconds} (15 s, a client setting),
 *       and changes by at most {@link #maxChangePerSecond} a second, so gusts don't swing the streaks.</li>
 *   <li><b>Gusts bend only new rain:</b> a drop keeps the lean it started falling with, so a change starts at the top and
 *       travels down the streak at the fall speed, as a bend. {@link #at} gives the lean of rain that started falling
 *       some ticks ago; the renderer builds each streak's shape from it.</li>
 * </ul>
 * Lean is horizontal blocks per block of fall, downwind. Rain is kept for a middling shower and scaled by fall speed for
 * others (harder rain falls faster, so leans less); snow is kept on its own. Pure Java, tested.
 */
public final class RainSlant {

    /** The averaging time for the wind the lean follows, seconds (client setting). */
    public static volatile double averageSeconds = 15;
    /** The most the lean may change per second (client setting). */
    public static volatile double maxChangePerSecond = 0.1;
    /** The most a streak leans, so a gale doesn't lay them flat. */
    public static final double MAX_RAIN = 1.2;
    public static final double MAX_SNOW = 1.6;
    /** The rain strength the rain lean is kept for. */
    static final double REF_STRENGTH = 0.6;
    /** Ticks of history kept: longer than snow takes to fall through the drawn band. */
    public static final int HISTORY = 1024;

    private final double[] rainX = new double[HISTORY];
    private final double[] rainZ = new double[HISTORY];
    private final double[] snowX = new double[HISTORY];
    private final double[] snowZ = new double[HISTORY];
    private double avgX;
    private double avgZ;
    private final double[] rain = new double[2];
    private final double[] snow = new double[2];
    private long count;

    private static double fallMps(boolean snow, double strength) {
        return RainColumn.fallPerTick(snow, strength) * 20;
    }

    /** One tick with the surface wind now (m/s). */
    public void step(double windX, double windZ) {
        boolean first = count == 0;
        if (first) {
            avgX = windX;
            avgZ = windZ;
        } else {
            double a = 1 - Math.exp(-1 / Math.max(1, averageSeconds * 20));
            avgX += (windX - avgX) * a;
            avgZ += (windZ - avgZ) * a;
        }
        double maxStep = first ? Double.POSITIVE_INFINITY : Math.max(0, maxChangePerSecond) / 20;
        toward(rain, avgX / fallMps(false, REF_STRENGTH), avgZ / fallMps(false, REF_STRENGTH), MAX_RAIN, maxStep);
        toward(snow, avgX / fallMps(true, 0), avgZ / fallMps(true, 0), MAX_SNOW, maxStep);
        int i = (int) (count % HISTORY);
        rainX[i] = rain[0];
        rainZ[i] = rain[1];
        snowX[i] = snow[0];
        snowZ[i] = snow[1];
        count++;
    }

    private static void toward(double[] cur, double tx, double tz, double max, double maxStep) {
        double len = Math.hypot(tx, tz);
        if (len > max) {
            tx *= max / len;
            tz *= max / len;
        }
        double dx = tx - cur[0];
        double dz = tz - cur[1];
        double d = Math.hypot(dx, dz);
        if (d > maxStep) {
            dx *= maxStep / d;
            dz *= maxStep / d;
        }
        cur[0] += dx;
        cur[1] += dz;
    }

    public void reset() {
        count = 0;
        rain[0] = rain[1] = snow[0] = snow[1] = 0;
    }

    /**
     * The lean of rain (or snow) of {@code strength} that started falling {@code ticksAgo} ticks ago (0 = now; further
     * back than the history gives the oldest kept), into {@code out} {x, z}.
     */
    public void at(boolean snowy, double strength, double ticksAgo, double[] out) {
        if (count == 0) {
            out[0] = out[1] = 0;
            return;
        }
        double newest = count - 1;
        double oldest = Math.max(0, count - HISTORY);
        double t = Math.max(oldest, Math.min(newest, newest - Math.max(0, ticksAgo)));
        long t0 = (long) Math.floor(t);
        long t1 = Math.min((long) newest, t0 + 1);
        double f = t - t0;
        int i0 = (int) (t0 % HISTORY);
        int i1 = (int) (t1 % HISTORY);
        double[] xs = snowy ? snowX : rainX;
        double[] zs = snowy ? snowZ : rainZ;
        double x = xs[i0] + (xs[i1] - xs[i0]) * f;
        double z = zs[i0] + (zs[i1] - zs[i0]) * f;
        if (!snowy) {
            double scale = fallMps(false, REF_STRENGTH) / fallMps(false, strength);
            x *= scale;
            z *= scale;
            double len = Math.hypot(x, z);
            if (len > MAX_RAIN) {
                x *= MAX_RAIN / len;
                z *= MAX_RAIN / len;
            }
        }
        out[0] = x;
        out[1] = z;
    }
}
