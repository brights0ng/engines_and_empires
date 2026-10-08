package dev.brights0ng.enginesandempires.weather.sim;

import java.util.List;

/**
 * Pressure and wind from the weather systems and the jet (phase 2 of {@code claude/weather-backbone-plan.md}). Pure.
 *
 * <ul>
 *   <li><b>Pressure:</b> {@value #NORMAL} hPa plus each system's anomaly, falling off as exp(-(d/r)^2).</li>
 *   <li><b>Balanced ("geostrophic") wind:</b> air flows along the isobars, not across them, because of Earth's turning:
 *       counterclockwise round lows in a northern-style zone, clockwise in a mirrored one; faster where isobars are
 *       closer. Scaled so a mature low (25 hPa, radius 4000) gives about 15 m/s at the surface.</li>
 *   <li><b>Surface wind:</b> slowed by friction to 70% and turned {@value #FRICTION_TURN_DEGREES} degrees in toward low
 *       pressure, as near the ground.</li>
 *   <li><b>Aloft wind:</b> the jet plus the systems' balanced wind, stronger up high.</li>
 * </ul>
 * Gusts come later (phase 4, with convection); for now a gentle, slowly varying gustiness keeps ships from sitting in
 * perfectly steady wind.
 */
public final class PressureField {

    public static final double NORMAL = 1013;
    /** m/s of balanced wind per hPa per block of pressure gradient. */
    static final double BALANCE = 2800;
    static final double FRICTION_SPEED = 0.7;
    static final double FRICTION_TURN_DEGREES = 25;
    /**
     * How much of the systems' balanced wind blows aloft (on top of the jet). Was 1.3; halved with the jet (Bright,
     * 2026-10-06: clouds drifted 12-18 blocks a second near storms, about three times faster than a real sky feels).
     */
    static final double ALOFT_SYSTEMS = 0.65;
    static final double MAX_WIND = 40;
    /** Systems further than this many radii away are ignored. */
    static final double REACH = 3.5;

    /** The pressure at (x, z), hPa. */
    public static double pressure(List<WeatherSystem> systems, double x, double z) {
        double p = NORMAL;
        for (WeatherSystem s : systems) {
            double r = s.radius();
            double dx = x - s.x;
            double dz = z - s.z;
            double d2 = dx * dx + dz * dz;
            if (d2 > REACH * REACH * r * r) {
                continue;
            }
            p += s.signedStrength() * Math.exp(-d2 / (r * r));
        }
        return p;
    }

    /** The pressure gradient at (x, z), hPa per block. {d/dx, d/dz} */
    public static double[] gradient(List<WeatherSystem> systems, double x, double z) {
        double gx = 0;
        double gz = 0;
        for (WeatherSystem s : systems) {
            double r = s.radius();
            double dx = x - s.x;
            double dz = z - s.z;
            double d2 = dx * dx + dz * dz;
            if (d2 > REACH * REACH * r * r) {
                continue;
            }
            double e = s.signedStrength() * Math.exp(-d2 / (r * r)) * (-2 / (r * r));
            gx += e * dx;
            gz += e * dz;
        }
        return new double[]{gx, gz};
    }

    /**
     * The balanced wind for gradient {@code g} in a zone turning {@code hem}, m/s. {x, z}. Minecraft's +z is south, so
     * for northern-style turning the wind is {@code BALANCE * (dp/dz, -dp/dx)}: on a low's east side it blows north.
     */
    public static double[] balanced(double[] g, int hem) {
        return new double[]{hem * BALANCE * g[1], -hem * BALANCE * g[0]};
    }

    /** The surface wind from the balanced wind {@code v} and gradient {@code g}: slowed and turned toward low pressure. */
    public static double[] surface(double[] v, double[] g) {
        double speed = Math.hypot(v[0], v[1]) * FRICTION_SPEED;
        if (speed < 1e-9) {
            return new double[]{0, 0};
        }
        double gl = Math.hypot(g[0], g[1]);
        double ux = v[0] / Math.hypot(v[0], v[1]);
        double uz = v[1] / Math.hypot(v[0], v[1]);
        double lx = gl < 1e-12 ? 0 : -g[0] / gl;
        double lz = gl < 1e-12 ? 0 : -g[1] / gl;
        double a = Math.toRadians(FRICTION_TURN_DEGREES);
        double x = Math.cos(a) * ux + Math.sin(a) * lx;
        double z = Math.cos(a) * uz + Math.sin(a) * lz;
        double len = Math.hypot(x, z);
        return new double[]{x / len * speed, z / len * speed};
    }

    /** A slow gust factor around 1 at (x, z) and time {@code seconds}: within about 15%, changing over tens of seconds. */
    public static double gust(double x, double z, double seconds, long seed) {
        double a = Math.sin(seconds * 0.21 + x * 0.0013 + SimMath.unit(seed, 3) * 6.28);
        double b = Math.sin(seconds * 0.53 + z * 0.0017 + SimMath.unit(seed, 4) * 6.28);
        return 1 + 0.1 * a + 0.05 * b;
    }

    /** The full wind at (x, z): surface and aloft, m/s. {surfaceX, surfaceZ, aloftX, aloftZ} */
    public static double[] wind(List<WeatherSystem> systems, JetStream jet, double x, double z, double seconds,
                                double season, long seed) {
        return windFrom(gradient(systems, x, z), jet, x, z, seconds, season, seed, true);
    }

    /** The full wind at (x, z) from a {@link Snapshot}: the same as {@link #wind}, much cheaper per call. */
    public static double[] wind(Snapshot systems, JetStream jet, double x, double z, double seconds, double season,
                                long seed) {
        return windFrom(systems.gradient(x, z), jet, x, z, seconds, season, seed, true);
    }

    /** As {@link #wind(Snapshot, JetStream, double, double, double, double, long)} without the gusts (what clouds ride). */
    public static double[] steadyWind(Snapshot systems, JetStream jet, double x, double z, double seconds,
                                      double season, long seed) {
        return windFrom(systems.gradient(x, z), jet, x, z, seconds, season, seed, false);
    }

    private static double[] windFrom(double[] g, JetStream jet, double x, double z, double seconds, double season,
                                     long seed, boolean gusts) {
        double[] v = balanced(g, jet.hemisphereAt(z));
        double[] surface = surface(v, g);
        double gust = gusts ? gust(x, z, seconds, seed) : 1;
        double[] jetWind = jet.aloftWind(x, z, seconds, season);
        double ax = jetWind[0] + ALOFT_SYSTEMS * v[0];
        double az = jetWind[1] + ALOFT_SYSTEMS * v[1];
        return new double[]{
                clampWind(surface[0] * gust), clampWind(surface[1] * gust), clampWind(ax), clampWind(az)};
    }

    private static double clampWind(double v) {
        return Math.max(-MAX_WIND, Math.min(MAX_WIND, v));
    }

    /**
     * The systems' pressure shapes frozen for one step (perf report 2026-10-05): each system's centre, radius and
     * signed strength worked out once (they cost a life-fraction and smoothsteps per call otherwise), in flat arrays.
     * {@link #near} gives a copy with only the systems that can reach a rectangle, for per-tile work.
     */
    public static final class Snapshot {

        final int n;
        final double[] x;
        final double[] z;
        /** 1 / r^2 */
        final double[] inv;
        final double[] strength;
        /** (REACH r)^2 */
        final double[] reach2;
        final double[] reach;

        private Snapshot(int n) {
            this.n = n;
            this.x = new double[n];
            this.z = new double[n];
            this.inv = new double[n];
            this.strength = new double[n];
            this.reach2 = new double[n];
            this.reach = new double[n];
        }

        public static Snapshot of(List<WeatherSystem> systems) {
            Snapshot s = new Snapshot(systems.size());
            for (int i = 0; i < s.n; i++) {
                WeatherSystem w = systems.get(i);
                double r = w.radius();
                s.x[i] = w.x;
                s.z[i] = w.z;
                s.inv[i] = 1 / (r * r);
                s.strength[i] = w.signedStrength();
                s.reach[i] = REACH * r;
                s.reach2[i] = s.reach[i] * s.reach[i];
            }
            return s;
        }

        /** Only the systems whose reach overlaps the rectangle [minX, maxX] x [minZ, maxZ]. */
        public Snapshot near(double minX, double minZ, double maxX, double maxZ) {
            int count = 0;
            boolean[] keep = new boolean[n];
            for (int i = 0; i < n; i++) {
                double dx = Math.max(0, Math.max(minX - x[i], x[i] - maxX));
                double dz = Math.max(0, Math.max(minZ - z[i], z[i] - maxZ));
                if (dx * dx + dz * dz <= reach2[i]) {
                    keep[i] = true;
                    count++;
                }
            }
            Snapshot s = new Snapshot(count);
            int j = 0;
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    s.x[j] = x[i];
                    s.z[j] = z[i];
                    s.inv[j] = inv[i];
                    s.strength[j] = strength[i];
                    s.reach[j] = reach[i];
                    s.reach2[j] = reach2[i];
                    j++;
                }
            }
            return s;
        }

        public int size() {
            return n;
        }

        /** The pressure at (x, z), hPa. */
        public double pressure(double px, double pz) {
            double p = NORMAL;
            for (int i = 0; i < n; i++) {
                double dx = px - x[i];
                double dz = pz - z[i];
                double d2 = dx * dx + dz * dz;
                if (d2 <= reach2[i]) {
                    p += strength[i] * Math.exp(-d2 * inv[i]);
                }
            }
            return p;
        }

        /** The pressure gradient at (x, z), hPa per block. {d/dx, d/dz} */
        public double[] gradient(double px, double pz) {
            double gx = 0;
            double gz = 0;
            for (int i = 0; i < n; i++) {
                double dx = px - x[i];
                double dz = pz - z[i];
                double d2 = dx * dx + dz * dz;
                if (d2 <= reach2[i]) {
                    double e = strength[i] * Math.exp(-d2 * inv[i]) * (-2 * inv[i]);
                    gx += e * dx;
                    gz += e * dz;
                }
            }
            return new double[]{gx, gz};
        }
    }

    private PressureField() {
    }
}
