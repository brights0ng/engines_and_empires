package dev.brights0ng.enginesandempires.weather.sim;

/**
 * The jet stream: the west-to-east flow high up that carries the weather systems along (Bright, 2026-10-05: weather
 * always comes from the west). Pure; everything is a function of position, time and season, so it needs no saving.
 *
 * <h2>Storm tracks</h2>
 * One track every {@link SimParams#trackSpacing half band period} along Z, at {@code z = k * trackSpacing}. In
 * {@code bands} mode the tracks sit where the climate bands cross from cold to warm:
 * <ul>
 *   <li>even {@code k} (spawn's track, k = 0): cold to the north, like Earth's northern hemisphere ({@link #hemisphere}
 *       +1): lows turn counterclockwise;</li>
 *   <li>odd {@code k}: cold to the south, a mirrored zone like the southern hemisphere (-1): lows turn clockwise and
 *       their fronts are mirrored. Weather still comes from the west (Bright, 2026-10-05).</li>
 * </ul>
 * In summer each band-mode track shifts toward its cold side, in winter toward its warm side. In biome mode the tracks
 * keep the same spacing, all with northern-style turning and no seasonal shift.
 *
 * <h2>Meanders</h2>
 * Each track wanders north and south as two slow travelling waves (wavelengths about 2.5 and 4 system spacings,
 * drifting east at a quarter and an eighth of the steering speed), with a phase of its own, so tracks don't move in
 * lockstep.
 */
public final class JetStream {

    /** How far a track wanders from its centre, as a share of the band period. */
    static final double MEANDER = 0.08;
    /** How far a track shifts with the seasons (bands mode), as a share of the band period. */
    static final double SEASON_SHIFT = 0.06;
    /** The jet's half-width, as a share of the band period. */
    static final double WIDTH = 0.12;

    private final SimParams params;
    private final long seed;

    public JetStream(SimParams params, long seed) {
        this.params = params;
        this.seed = seed;
    }

    public SimParams params() {
        return params;
    }

    /** The track nearest {@code z}. */
    public int nearestTrack(double z) {
        return (int) Math.round(z / params.trackSpacing());
    }

    /** +1 for northern-style turning, -1 for a mirrored zone. */
    public int hemisphere(int track) {
        return params.bands() && (track & 1) != 0 ? -1 : 1;
    }

    /** +1 or -1 by the nearest track to {@code z}. */
    public int hemisphereAt(double z) {
        return hemisphere(nearestTrack(z));
    }

    /** Where track {@code k} crosses {@code x} at time {@code seconds}, in season {@code s}. */
    public double trackZ(int k, double x, double seconds, double s) {
        double centre = k * params.trackSpacing();
        if (params.bands()) {
            // The cold side is -Z for a northern-style track, +Z for a mirrored one.
            centre -= hemisphere(k) * SEASON_SHIFT * params.bandPeriod() * clamp(s);
        }
        return centre + meander(k, x, seconds, false);
    }

    /** The track's slope dz/dx at {@code x}. */
    public double slope(int k, double x, double seconds) {
        return meander(k, x, seconds, true);
    }

    private double meander(int k, double x, double seconds, boolean derivative) {
        double a = MEANDER * params.bandPeriod();
        double l1 = 2.5 * params.spacing();
        double l2 = 4.0 * params.spacing();
        double c1 = 0.25 * params.speed();
        double c2 = 0.125 * params.speed();
        long h = SimMath.hash(seed, 0x7E7L, k);
        double p1 = 2 * Math.PI * SimMath.unit(h, 1);
        double p2 = 2 * Math.PI * SimMath.unit(h, 2);
        double w1 = 2 * Math.PI / l1;
        double w2 = 2 * Math.PI / l2;
        double arg1 = w1 * (x - c1 * seconds) + p1;
        double arg2 = w2 * (x - c2 * seconds) + p2;
        if (derivative) {
            return a * w1 * Math.cos(arg1) + 0.4 * a * w2 * Math.cos(arg2);
        }
        return a * Math.sin(arg1) + 0.4 * a * Math.sin(arg2);
    }

    /** How strongly the jet blows at (x, z), 0.4 far from any track to 1 on one. */
    public double profile(double x, double z, double seconds, double s) {
        int k = nearestTrack(z);
        double d = z - trackZ(k, x, seconds, s);
        double w = WIDTH * params.bandPeriod();
        return 0.4 + 0.6 * Math.exp(-(d * d) / (w * w));
    }

    /** The jet's direction at (x, z): along the nearest track, eastward. {x, z}, unit length. */
    public double[] direction(double x, double z, double seconds) {
        double m = slope(nearestTrack(z), x, seconds);
        double len = Math.sqrt(1 + m * m);
        return new double[]{1 / len, m / len};
    }

    /** The aloft wind from the jet at (x, z), m/s. {x, z} */
    public double[] aloftWind(double x, double z, double seconds, double s) {
        double[] d = direction(x, z, seconds);
        double speed = params.jetCore() * SimParams.Seasonal.of(s, 0).jet() * profile(x, z, seconds, s);
        return new double[]{d[0] * speed, d[1] * speed};
    }

    /** How systems are steered at (x, z), blocks per tick. {x, z} */
    public double[] steering(double x, double z, double seconds, double s) {
        double[] d = direction(x, z, seconds);
        double perTick = params.speed() * SimParams.Seasonal.of(s, 0).speed() / 20.0
                * (0.5 + 0.5 * (profile(x, z, seconds, s) - 0.4) / 0.6);
        return new double[]{d[0] * perTick, d[1] * perTick};
    }

    private static double clamp(double s) {
        return Double.isFinite(s) ? Math.max(-1, Math.min(1, s)) : 0;
    }
}
