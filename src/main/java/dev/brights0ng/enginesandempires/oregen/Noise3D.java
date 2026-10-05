package dev.brights0ng.enginesandempires.oregen;

/**
 * 3D gradient noise ("improved Perlin"), with each lattice corner's gradient chosen by hashing the
 * corner's coordinates together with the seed. Output is in [-1, 1] and averages 0.
 *
 * <p>Only basic arithmetic is used, so results are identical on every JVM and machine. Instances are
 * immutable and thread-safe.
 */
public final class Noise3D {

    private static final int[][] GRADIENTS = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };

    private final long seed;

    public Noise3D(long seed) {
        this.seed = seed;
    }

    public double noise(double x, double y, double z) {
        int x0 = floor(x);
        int y0 = floor(y);
        int z0 = floor(z);
        double fx = x - x0;
        double fy = y - y0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fy);
        double w = fade(fz);

        double x00 = lerp(u, corner(x0, y0, z0, fx, fy, fz), corner(x0 + 1, y0, z0, fx - 1, fy, fz));
        double x10 = lerp(u, corner(x0, y0 + 1, z0, fx, fy - 1, fz), corner(x0 + 1, y0 + 1, z0, fx - 1, fy - 1, fz));
        double x01 = lerp(u, corner(x0, y0, z0 + 1, fx, fy, fz - 1), corner(x0 + 1, y0, z0 + 1, fx - 1, fy, fz - 1));
        double x11 = lerp(u, corner(x0, y0 + 1, z0 + 1, fx, fy - 1, fz - 1), corner(x0 + 1, y0 + 1, z0 + 1, fx - 1, fy - 1, fz - 1));

        return lerp(w, lerp(v, x00, x10), lerp(v, x01, x11));
    }

    private double corner(int ix, int iy, int iz, double dx, double dy, double dz) {
        int[] g = GRADIENTS[(int) ((Hashing.cell(seed, ix, iy, iz) >>> 40) % GRADIENTS.length)];
        return g[0] * dx + g[1] * dy + g[2] * dz;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
