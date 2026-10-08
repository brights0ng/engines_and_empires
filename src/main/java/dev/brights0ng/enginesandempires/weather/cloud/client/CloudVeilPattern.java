package dev.brights0ng.enginesandempires.weather.cloud.client;

/**
 * The veils' pattern as cloud_veil.fsh makes it, in Java, for the tests' pictures and checks (2026-10-07 evening).
 * Keep in step with the shader.
 */
public final class CloudVeilPattern {

    static double hash(double cx, double cy) {
        int qx = (int) cx, qy = (int) cy;
        int h = (qx * 1597334677) ^ (qy * (int) 3812015801L);
        h ^= h >>> 16;
        h *= (int) 2246822519L;
        h ^= h >>> 13;
        return (h & 0xFFFFFFFFL) / 4294967295.0;
    }

    static double noise(double px, double py) {
        double ix = Math.floor(px), iy = Math.floor(py);
        double fx = px - ix, fy = py - iy;
        double ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        double a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        double ab = a + (b - a) * ux, cd = c + (d - c) * ux;
        return ab + (cd - ab) * uy;
    }

    static double fbm(double px, double py) {
        double s = 0, a = 0.5;
        for (int k = 0; k < 4; k++) {
            s += a * noise(px, py);
            px = px * 2.03 + 17.1;
            py = py * 2.03 + 9.3;
            a *= 0.5;
        }
        return s / 0.9375;
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /**
     * The veil's opacity at deck-frame point (px, pz) with the wind toward (wx, wz) (unit), under cirrostratus cover
     * {@code coverCs} and cirrus cover {@code coverCi}, before the distance fade.
     */
    public static double alpha(double px, double pz, double wx, double wz, double coverCs, double coverCi) {
        double u = px * wx + pz * wz, v = -px * wz + pz * wx;
        double edge = fbm(px / 700.0 + 3.7, pz / 700.0 + 3.7) - 0.5;
        double cs = smoothstep(0.1, 0.8, coverCs + 0.45 * edge);
        double ci = smoothstep(0.1, 0.8, coverCi + 0.45 * edge);
        if (cs <= 0 && ci <= 0) {
            return 0;
        }
        double bend = fbm(u / 1800.0 + 5.1, v / 1000.0 + 5.1);
        double wobble = fbm(u / 600.0 + 15.0, v / 400.0 + 15.0);
        double fs = v / 210.0 + 1.6 * bend + 0.35 * wobble;
        double f = fs - Math.floor(fs);
        double width = 0.3 + 0.35 * fbm(u / 800.0 + 19.0, v / 300.0 + 19.0);
        double strip = smoothstep(0.0, 0.15, f) * (1 - smoothstep(width, width + 0.22, f));
        double fib = fbm(u / 450.0, v / 24.0);
        double breaks = smoothstep(0.3, 0.55, fbm(u / 900.0 + 7.3, v / 260.0 + 7.3));
        double stripA = 0.8 * strip * breaks * (0.45 + 0.55 * fib);
        double veil = smoothstep(0.52, 0.7, fbm(px / 6000.0 + 2.2, pz / 6000.0 + 2.2));
        double veilA = 0.3 * (0.7 + 0.3 * fbm(u / 300.0 + 13.0, v / 60.0 + 13.0));
        double aCs = stripA + (veilA - stripA) * veil;

        double warpU = fbm(u / 600.0 + 11.0, v / 300.0 + 11.0) - 0.5;
        double warpV = fbm(u / 600.0 + 23.0, v / 300.0 + 23.0) - 0.5;
        double fibres = fbm((u + warpU * 500.0) / 340.0 + 31.0, (v + warpV * 160.0) / 30.0 + 31.0);
        double tufts = smoothstep(0.35, 0.65, fbm(px / 1400.0 + 41.0, pz / 1400.0 + 41.0));
        double aCi = 0.8 * smoothstep(0.44, 0.7, fibres) * tufts;

        return 1 - (1 - aCs * cs) * (1 - aCi * ci);
    }

    private CloudVeilPattern() {
    }
}
