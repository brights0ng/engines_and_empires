package dev.brights0ng.enginesandempires.oregen;

/**
 * 2D simplex noise with no lookup table: every lattice corner's gradient comes from hashing the
 * corner's coordinates together with the seed. Output is roughly in [-1, 1].
 *
 * <p>Uses only basic arithmetic plus {@link StrictMath} for the gradient table, so results are
 * identical on every JVM and machine. Instances are immutable and thread-safe.
 */
final class SimplexNoise2D {

    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

    /** Scales the raw sum of corner contributions so the output peaks close to 1. */
    private static final double NORMALIZATION = 99.2;

    private static final int GRADIENT_COUNT = 32; // must be a power of two
    private static final double[] GRADIENT_X = new double[GRADIENT_COUNT];
    private static final double[] GRADIENT_Y = new double[GRADIENT_COUNT];

    static {
        for (int i = 0; i < GRADIENT_COUNT; i++) {
            double angle = 2.0 * StrictMath.PI * i / GRADIENT_COUNT;
            GRADIENT_X[i] = StrictMath.cos(angle);
            GRADIENT_Y[i] = StrictMath.sin(angle);
        }
    }

    private final long seed;

    SimplexNoise2D(long seed) {
        this.seed = seed;
    }

    double noise(double x, double y) {
        // Skew the input space to find which simplex cell we are in.
        double skew = (x + y) * F2;
        int i = floor(x + skew);
        int j = floor(y + skew);

        // Unskew the cell origin back to input space and get the offset from it.
        double unskew = (i + j) * G2;
        double x0 = x - (i - unskew);
        double y0 = y - (j - unskew);

        // The cell is two triangles; work out which one we are in.
        int i1;
        int j1;
        if (x0 > y0) {
            i1 = 1;
            j1 = 0;
        } else {
            i1 = 0;
            j1 = 1;
        }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        return NORMALIZATION * (corner(i, j, x0, y0)
                + corner(i + i1, j + j1, x1, y1)
                + corner(i + 1, j + 1, x2, y2));
    }

    private double corner(int cornerX, int cornerY, double dx, double dy) {
        double falloff = 0.5 - dx * dx - dy * dy;
        if (falloff < 0.0) {
            return 0.0;
        }
        long hash = mix64(seed ^ ((long) cornerX * 0x9E3779B97F4A7C15L) ^ ((long) cornerY * 0xC2B2AE3D27D4EB4FL));
        int g = (int) (hash >>> 59); // top 5 bits -> 0..31
        falloff *= falloff;
        return falloff * falloff * (GRADIENT_X[g] * dx + GRADIENT_Y[g] * dy);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** SplitMix64 finalizer: a cheap, well-mixed 64-bit hash. */
    static long mix64(long z) {
        return Hashing.mix64(z);
    }
}
