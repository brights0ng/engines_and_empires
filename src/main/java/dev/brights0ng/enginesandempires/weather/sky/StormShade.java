package dev.brights0ng.enginesandempires.weather.sky;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;

/**
 * How much the clouds shade a place (weather phase 6d; Bright, 2026-10-09), pure Java, the same on the client (at the
 * camera: the sky, the light, the fog) and the server (at any block: the light that spawning, undead and daylight
 * sensors see).
 *
 * <ul>
 *   <li><b>Cover</b> (0-1): how much of the direct sunlight the clouds overhead block, from their optical depth
 *       ({@code 1 - exp(-depth)}). A fair-weather cumulus overhead covers the sun; this hides the sun, moon and stars
 *       even without rain.</li>
 *   <li><b>Gloom overhead</b> (0-1): how dark the cloud overhead makes the day. Mostly the cloud's storm darkness (its
 *       type) and its depth: a cumulonimbus or nimbostratus is gloomy, a congestus somewhat, a fair-weather cumulus or
 *       thin layer barely.</li>
 *   <li><b>Gloom around</b> (0-1): the average gloom over rings 400, 900 and 1500 blocks out, so the day darkens as a
 *       storm comes near and stays dim while one sits close.</li>
 *   <li><b>Darkness</b> (0-1): the two together, {@code 1 - (1 - overhead)(1 - }{@value #AROUND_WEIGHT}{@code  ×
 *       around)}. The light under it: {@link #lightFactor}.</li>
 * </ul>
 * Everything is continuous in position: a cloud's shade fades smoothly over the outer half of its radius, a wide anvil
 * adds a thinner shade beyond, so walking under a storm's edge darkens gradually rather than in steps.
 */
public final class StormShade {

    /** The most light the darkest storm takes from the day (vanilla's full thunderstorm takes about 0.53). */
    public static final double MAX_DIM = 0.7;
    /** How much the gloom around adds at most. */
    public static final double AROUND_WEIGHT = 0.45;
    /** Blocks of cloud (at density 1) for one unit of optical depth. */
    static final double DEPTH_SCALE = 120;
    /** Blocks of cloud depth worth one unit of gloom, besides the type's storm darkness. */
    static final double GLOOM_DEPTH = 4000;
    /** How strongly a type's storm darkness turns into gloom. */
    static final double STORM_GLOOM = 2.5;
    /** An anvil's thickness as a share of the cloud's depth, and how gloomy it is for its strength. */
    static final double ANVIL_DEPTH = 0.12;
    static final double ANVIL_GLOOM = 0.5;
    /** How much plain cover adds to the gloom: a passing cumulus's shadow dims the day a little. */
    static final double COVER_GLOOM = 0.25;
    static final double[] RINGS = {400, 900, 1500};
    static final int DIRECTIONS = 8;
    /** Index bucket size, blocks. */
    static final int BUCKET = 512;

    /** What the clouds do to a place. */
    public record Sample(double cover, double overhead, double around, double darkness) {

        public static final Sample CLEAR = new Sample(0, 0, 0, 0);
    }

    /** The share of the day's light left under {@code darkness} (1 clear, {@code 1 - MAX_DIM} the darkest storm). */
    public static double lightFactor(double darkness) {
        double d = Math.max(0, Math.min(1, darkness));
        return 1 - MAX_DIM * Math.pow(d, 1.3);
    }

    /** The clouds at a moment, bucketed for quick lookups by position. */
    public static final class Index {

        private final double time;
        private final Map<Long, List<CloudShape>> buckets = new HashMap<>();
        private final boolean empty;

        public Index(List<CloudShape> shapes, double time) {
            this.time = time;
            boolean any = false;
            for (CloudShape c : shapes) {
                if (!c.visible()) {
                    continue;
                }
                any = true;
                double r = reach(c);
                double x = c.xAt(time);
                double z = c.zAt(time);
                int x0 = Math.floorDiv((int) Math.floor(x - r), BUCKET);
                int x1 = Math.floorDiv((int) Math.floor(x + r), BUCKET);
                int z0 = Math.floorDiv((int) Math.floor(z - r), BUCKET);
                int z1 = Math.floorDiv((int) Math.floor(z + r), BUCKET);
                for (int bx = x0; bx <= x1; bx++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        buckets.computeIfAbsent(key(bx, bz), k -> new ArrayList<>()).add(c);
                    }
                }
            }
            this.empty = !any;
        }

        public double time() {
            return time;
        }

        public boolean isEmpty() {
            return empty;
        }

        List<CloudShape> near(double x, double z) {
            List<CloudShape> l = buckets.get(key(Math.floorDiv((int) Math.floor(x), BUCKET),
                    Math.floorDiv((int) Math.floor(z), BUCKET)));
            return l == null ? List.of() : l;
        }

        private static long key(int bx, int bz) {
            return ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
        }
    }

    /** Cover, gloom overhead, gloom around and darkness at (x, z). */
    public static Sample sample(Index index, double x, double z) {
        if (index.isEmpty()) {
            return Sample.CLEAR;
        }
        double[] here = column(index, x, z);
        double around = 0;
        int n = 0;
        for (double r : RINGS) {
            for (int i = 0; i < DIRECTIONS; i++) {
                // Each ring turned half a step from the last, so the samples don't line up.
                double a = (i + (n / DIRECTIONS) * 0.5) * (Math.PI * 2 / DIRECTIONS);
                around += column(index, x + r * Math.cos(a), z + r * Math.sin(a))[1];
                n++;
            }
        }
        around /= n;
        double overhead = here[1];
        double darkness = 1 - (1 - overhead) * (1 - AROUND_WEIGHT * around);
        return new Sample(here[0], overhead, around, darkness);
    }

    /** {cover, gloom} of the column at (x, z). */
    static double[] column(Index index, double x, double z) {
        double depth = 0;
        double gloom = 0;
        for (CloudShape c : index.near(x, z)) {
            double dx = x - c.xAt(index.time());
            double dz = z - c.zAt(index.time());
            double d = Math.sqrt(dx * dx + dz * dz);
            double body = Math.max(1, c.radius());
            double thick = Math.max(0, c.topY() - c.baseY());
            double pb = profile(d / body);
            if (pb > 0) {
                double life = c.lifecycle();
                double dens = c.effectiveDensity() * (0.5 + 0.5 * c.effectiveCoverage());
                depth += dens * thick / DEPTH_SCALE * pb;
                gloom += pb * life * (STORM_GLOOM * c.stormDarkness() + thick / GLOOM_DEPTH);
            }
            if (c.anvilStrength() > 0) {
                double anvilLife = Math.max(0, Math.min(1, c.growth() * (1 - c.anvilDecay())));
                double pa = profile(d / (body * (1 + 1.2 * c.anvilStrength())));
                if (pa > 0 && anvilLife > 0) {
                    double a = pa * anvilLife * c.anvilStrength();
                    depth += c.density() * thick * ANVIL_DEPTH / DEPTH_SCALE * a;
                    gloom += ANVIL_GLOOM * a * c.stormDarkness();
                }
            }
        }
        double cover = 1 - Math.exp(-depth);
        double g = 1 - Math.exp(-gloom);
        return new double[] {cover, 1 - (1 - g) * (1 - COVER_GLOOM * cover)};
    }

    /** How far a cloud's shade reaches from its centre, blocks. */
    static double reach(CloudShape c) {
        return Math.max(1, c.radius()) * (1 + 1.2 * Math.max(0, c.anvilStrength()));
    }

    /** 1 within half the radius, fading smoothly to 0 at it ({@code u} = distance / radius). */
    static double profile(double u) {
        if (u <= 0.5) {
            return 1;
        }
        if (u >= 1) {
            return 0;
        }
        double t = (u - 0.5) / 0.5;
        return 1 - t * t * (3 - 2 * t);
    }

    private StormShade() {
    }
}
