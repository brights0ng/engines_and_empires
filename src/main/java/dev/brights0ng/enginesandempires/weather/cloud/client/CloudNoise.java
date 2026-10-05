package dev.brights0ng.enginesandempires.weather.cloud.client;

/**
 * Small, fast gradient noise for cloud shapes, seeded per cloud. Values are roughly in [-1, 1]. The 3D version is used
 * with time as its third axis, so shapes churn: features grow, shrink and drift instead of sliding.
 *
 * <p>Self-contained (no Minecraft classes) so the voxelizer runs on a worker thread and in unit tests.
 */
final class CloudNoise {

    private static final double[] GRAD_X = new double[16];
    private static final double[] GRAD_Z = new double[16];

    /**
     * Perlin's 3D gradients: the 12 edge directions of a cube, padded to 16 with four repeats so one is picked with a
     * bit mask instead of a division.
     */
    private static final double[] G3X = {1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, -1, 0, 0};
    private static final double[] G3Y = {1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, 1, -1, -1};
    private static final double[] G3Z = {0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 0, 1, -1};

    static {
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI * 2 / 16;
            GRAD_X[i] = Math.cos(a);
            GRAD_Z[i] = Math.sin(a);
        }
    }

    /** Fractal noise: {@code octaves} layers, each half the size and half the strength of the last. */
    static double fbm(double x, double z, int seed, int octaves) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * gradient(x, z, seed + o * 1013);
            norm += amp;
            amp *= 0.5;
            x = x * 2 + 17.3;
            z = z * 2 - 9.1;
        }
        return sum / norm;
    }

    /** One layer of gradient noise, about [-1, 1]. */
    static double gradient(double x, double z, int seed) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double n00 = dot(x0, z0, fx, fz, seed);
        double n10 = dot(x0 + 1, z0, fx - 1, fz, seed);
        double n01 = dot(x0, z0 + 1, fx, fz - 1, seed);
        double n11 = dot(x0 + 1, z0 + 1, fx - 1, fz - 1, seed);
        double u = fade(fx);
        double v = fade(fz);
        double nx0 = n00 + u * (n10 - n00);
        double nx1 = n01 + u * (n11 - n01);
        return (nx0 + v * (nx1 - nx0)) * 1.4;
    }

    private static double dot(int ix, int iz, double dx, double dz, int seed) {
        int h = hash(ix, iz, seed) & 15;
        return GRAD_X[h] * dx + GRAD_Z[h] * dz;
    }

    /** Fractal 3D noise, like {@link #fbm} with a third axis (time, for clouds). */
    static double fbm3(double x, double y, double z, int seed, int octaves) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * gradient3(x, y, z, seed + o * 1013);
            norm += amp;
            amp *= 0.5;
            x = x * 2 + 17.3;
            y = y * 2 - 9.1;
            z = z * 2 + 4.7;
        }
        return sum / norm;
    }

    /** One layer of 3D gradient (Perlin) noise, about [-1, 1]. */
    static double gradient3(double x, double y, double z, int seed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fy = y - y0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fy);
        double w = fade(fz);
        double n000 = dot3(x0, y0, z0, fx, fy, fz, seed);
        double n100 = dot3(x0 + 1, y0, z0, fx - 1, fy, fz, seed);
        double n010 = dot3(x0, y0 + 1, z0, fx, fy - 1, fz, seed);
        double n110 = dot3(x0 + 1, y0 + 1, z0, fx - 1, fy - 1, fz, seed);
        double n001 = dot3(x0, y0, z0 + 1, fx, fy, fz - 1, seed);
        double n101 = dot3(x0 + 1, y0, z0 + 1, fx - 1, fy, fz - 1, seed);
        double n011 = dot3(x0, y0 + 1, z0 + 1, fx, fy - 1, fz - 1, seed);
        double n111 = dot3(x0 + 1, y0 + 1, z0 + 1, fx - 1, fy - 1, fz - 1, seed);
        double x00 = n000 + u * (n100 - n000);
        double x10 = n010 + u * (n110 - n010);
        double x01 = n001 + u * (n101 - n001);
        double x11 = n011 + u * (n111 - n011);
        double y0v = x00 + v * (x10 - x00);
        double y1v = x01 + v * (x11 - x01);
        return (y0v + w * (y1v - y0v)) * 1.1;
    }

    private static double dot3(int ix, int iy, int iz, double dx, double dy, double dz, int seed) {
        int h = hash(ix, iy * 0x632be5ab + iz, seed) & 15;
        return G3X[h] * dx + G3Y[h] * dy + G3Z[h] * dz;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static int hash(int x, int z, int seed) {
        int h = seed;
        h ^= x * 0x27d4eb2d;
        h = Integer.rotateLeft(h, 13) * 0x165667b1;
        h ^= z * 0x9e3779b9;
        h = Integer.rotateLeft(h, 17) * 0x85ebca6b;
        h ^= h >>> 15;
        return h;
    }

    private CloudNoise() {
    }
}
