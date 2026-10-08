package dev.brights0ng.enginesandempires.weather.rain;

/**
 * The shape of the rain streaks of one kind (snow, or rain of about one strength) around the camera: how far sideways
 * a streak is at each height from where it crosses the pivot height (eye level), and where it meets the ground (Bright,
 * 2026-10-04).
 *
 * <ul>
 *   <li><b>Pivot at eye level:</b> streaks are spaced evenly where they cross the pivot height, so the rain is even in
 *       the air whatever the ground below does (one streak per landing block made slopes along the wind look twice as
 *       wet and slopes against it half). When the lean changes, a streak's top and bottom swing half as far each.</li>
 *   <li><b>Bends:</b> the lean at each height is the lean when that rain started falling ({@link RainSlant#at}), so a
 *       gust shows as a bend travelling down.</li>
 * </ul>
 * Offsets are kept at nodes every {@link #STEP} blocks across the drawn band and joined by straight lines; below the
 * band the bottom lean carries on, above it the top one. Pure Java, tested.
 *
 * <p><b>Shift</b> (2026-10-08, second pass): the whole shape can be moved sideways by a shift that {@link #build}
 * leaves alone. Each time the renderer rebuilds the shape (the band follows the camera, the lean changes) it
 * {@link #pin pins} the new shape to where the old one was at eye level, so the streaks there never move sideways.
 * Moving the band with the camera then changes nothing in the world: the streaks are a fixed set of slanted lines
 * the camera moves through, however fast it climbs or falls. (The first pass measured offsets from an anchor height
 * that eased after the camera over ~10 s: after a fast climb or drop the streaks slid sideways while it caught up.)
 */
public final class StreakShape {

    public static final int STEP = 4;
    /** How far a streak may bend from a straight line (blocks) and still be drawn as one. */
    static final double STRAIGHT = 0.05;

    /** The height of the ground's top face at a block column (as a heightmap gives it: the first free y). */
    @FunctionalInterface
    public interface Ground {
        /** The top surface's height at block column (x, z), world y (fractional on a ship's deck). */
        double height(int x, int z);
    }

    private final int down;
    private final int nodes;
    private final double[] dx;
    private final double[] dz;
    private final double[] sx;
    private final double[] sz;
    private final double[] lean = new double[2];
    private double pivot;
    private boolean straight;
    private double shiftX;
    private double shiftZ;

    /** For a band from {@code down} below the pivot to {@code up} above it (multiples of {@link #STEP}). */
    public StreakShape(int down, int up) {
        this.down = down;
        this.nodes = (down + up) / STEP + 1;
        dx = new double[nodes];
        dz = new double[nodes];
        sx = new double[nodes];
        sz = new double[nodes];
    }

    /**
     * Builds the shape for rain (or snow) of {@code strength} with the pivot at {@code pivot}, rain coming into view at
     * {@code top} (world y; the lean at each height is the one rain had when it passed {@code top}).
     */
    public StreakShape build(RainSlant slant, boolean snow, double strength, double pivot, double top) {
        return build(slant, snow ? Precip.SNOW : Precip.RAIN, strength, pivot, top);
    }

    /**
     * As {@link #build(RainSlant, boolean, double, double, double)} for {@code kind} (sleet, hail, ...). The sideways
     * offsets are measured from where a streak crosses the pivot height, plus the shift, which is kept as it was.
     */
    public StreakShape build(RainSlant slant, Precip kind, double strength, double pivot, double top) {
        this.pivot = pivot;
        double v = RainColumn.fallPerTick(kind, strength);
        for (int j = 0; j < nodes; j++) {
            double y = nodeY(j);
            slant.at(kind, strength, Math.max(0, top - y) / v, lean);
            sx[j] = lean[0];
            sz[j] = lean[1];
        }
        int j0 = down / STEP;
        dx[j0] = 0;
        dz[j0] = 0;
        for (int j = j0 - 1; j >= 0; j--) {
            // Further down is further downwind.
            dx[j] = dx[j + 1] + STEP * (sx[j] + sx[j + 1]) / 2;
            dz[j] = dz[j + 1] + STEP * (sz[j] + sz[j + 1]) / 2;
        }
        for (int j = j0 + 1; j < nodes; j++) {
            dx[j] = dx[j - 1] - STEP * (sx[j] + sx[j - 1]) / 2;
            dz[j] = dz[j - 1] - STEP * (sz[j] + sz[j - 1]) / 2;
        }
        straight = true;
        int last = nodes - 1;
        for (int j = 1; j < last && straight; j++) {
            double f = j / (double) last;
            if (Math.abs(dx[j] - (dx[0] + (dx[last] - dx[0]) * f)) > STRAIGHT
                    || Math.abs(dz[j] - (dz[0] + (dz[last] - dz[0]) * f)) > STRAIGHT) {
                straight = false;
            }
        }
        return this;
    }

    /**
     * Shifts the whole shape sideways so that at world height {@code y} a streak is where it was at
     * ({@code oldX}, {@code oldZ}) (from {@link #x}/{@link #z} before the last {@link #build}): the streaks there stay
     * put, and a band that only moved leaves every streak where it was.
     */
    public void pin(double y, double oldX, double oldZ) {
        shiftX += oldX - x(y);
        shiftZ += oldZ - z(y);
    }

    public double shiftX() {
        return shiftX;
    }

    public double shiftZ() {
        return shiftZ;
    }

    public void setShift(double x, double z) {
        shiftX = x;
        shiftZ = z;
    }

    public double pivot() {
        return pivot;
    }

    /** Whether the streaks are near enough straight to draw as one piece. */
    public boolean straight() {
        return straight;
    }

    public int nodes() {
        return nodes;
    }

    /** Node {@code j}'s height, world y. */
    public double nodeY(int j) {
        return pivot - down + j * STEP;
    }

    /**
     * How far sideways (x) a streak is at world height {@code y} from its spot (where it would cross the pivot height
     * with no shift).
     */
    public double x(double y) {
        return shiftX + offset(dx, sx, y);
    }

    public double z(double y) {
        return shiftZ + offset(dz, sz, y);
    }

    private double offset(double[] d, double[] s, double y) {
        double t = (y - (pivot - down)) / STEP;
        if (t <= 0) {
            return d[0] - s[0] * t * STEP;
        }
        int last = nodes - 1;
        if (t >= last) {
            return d[last] - s[last] * (t - last) * STEP;
        }
        int j = (int) t;
        double f = t - j;
        return d[j] + (d[j + 1] - d[j]) * f;
    }

    /**
     * Where the streak through (px, pivot, pz) first meets the ground, tracing down from {@code from} to {@code floor}
     * (world y): returns the height and puts the spot {x, z} in {@code out}. Rain that would land above {@code from}
     * lands at {@code from}; none found by {@code floor} lands at {@code floor}.
     */
    public double land(double px, double pz, double from, double floor, Ground ground, double[] out) {
        double clear = Double.NaN;
        double y = from;
        while (true) {
            if (blocked(px, pz, y, ground)) {
                break;
            }
            clear = y;
            if (y <= floor) {
                return put(px, pz, floor, out);
            }
            y = Math.max(floor, y - STEP);
        }
        if (Double.isNaN(clear)) {
            return put(px, pz, from, out);
        }
        double lo = y;
        double hi = clear;
        for (int i = 0; i < 4; i++) {
            double mid = (lo + hi) / 2;
            if (blocked(px, pz, mid, ground)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double g = ground.height((int) Math.floor(px + x(lo)), (int) Math.floor(pz + z(lo)));
        return put(px, pz, Math.max(lo, Math.min(hi, g)), out);
    }

    private boolean blocked(double px, double pz, double y, Ground ground) {
        return ground.height((int) Math.floor(px + x(y)), (int) Math.floor(pz + z(y))) > y;
    }

    private double put(double px, double pz, double y, double[] out) {
        out[0] = px + x(y);
        out[1] = pz + z(y);
        return y;
    }
}
