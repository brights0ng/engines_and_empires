package dev.brights0ng.enginesandempires.weather.cloud.client;

/**
 * The wisps' texture, made in code (pure Java, so it can be tested and looked at): opacity only (the sprites are
 * coloured per wisp). Two squares side by side:
 * <ul>
 *   <li><b>Left, haze:</b> a soft round blob with a lumpy edge and a mottled inside, fading to nothing at its rim.</li>
 *   <li><b>Right, shred:</b> torn vertical fibres, attached at the top, thinning to ragged ends below.</li>
 * </ul>
 * Both fall to zero well inside the square, so a sprite's own edge never shows.
 */
final class WispTexture {

    /** Side of each square, pixels. */
    static final int SIZE = 128;

    /** Opacity 0-255, {@code size} rows of {@code 2 * size} pixels. */
    static int[][] make(int size) {
        int[][] a = new int[size][size * 2];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double u = (x + 0.5) / size, v = (y + 0.5) / size;
                a[y][x] = (int) Math.round(255 * haze(u, v));
                a[y][x + size] = (int) Math.round(255 * shred(u, v));
            }
        }
        return a;
    }

    /** The haze's opacity at (u, v), each 0-1 across the square. */
    static double haze(double u, double v) {
        double dx = u * 2 - 1, dy = v * 2 - 1;
        double r = Math.sqrt(dx * dx + dy * dy);
        double ang = Math.atan2(dy, dx);
        // A lumpy rim: the edge sits between 0.7 and 0.95 of the way out, by angle.
        double edge = 0.82 + 0.13 * CloudNoise.gradient3(Math.cos(ang) * 1.6, Math.sin(ang) * 1.6, 0.5, 7101);
        double t = r / edge;
        if (t >= 1) {
            return 0;
        }
        double falloff = (1 - t * t) * (1 - t * t);
        double mottle = 0.7 + 0.3 * CloudNoise.gradient3(u * 5, v * 5, 1.5, 7102);
        return clamp01(falloff * mottle);
    }

    /** The shred's opacity at (u, v): u across, v down from where it hangs. */
    static double shred(double u, double v) {
        double across = 1 - (u * 2 - 1) * (u * 2 - 1);
        if (across <= 0) {
            return 0;
        }
        // Soft, broad fibres: noise stretched along the shred (fine fibres read as rain streaks).
        double n = CloudNoise.gradient3(u * 4, v * 1.0, 2.5, 7103) + 0.5 * CloudNoise.gradient3(u * 9, v * 2.0, 3.5, 7104);
        double fibre = clamp01((n + 0.5) * 1.3);
        // Attached softly at the top; torn, uneven ends below.
        double top = smooth(v / 0.18);
        double end = 0.45 + 0.3 * CloudNoise.gradient3(u * 4, 0.5, 4.5, 7105);
        double bottom = (1 - smooth((v - end) / 0.35)) * (1 - smooth((v - 0.85) / 0.15));
        return clamp01(fibre * across * top * bottom * 0.9);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private static double smooth(double t) {
        double c = clamp01(t);
        return c * c * (3 - 2 * c);
    }

    private WispTexture() {
    }
}
