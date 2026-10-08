package dev.brights0ng.enginesandempires.weather.climate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The regional climate: biome climates sampled on a coarse lattice and smoothed, so the atmosphere sees regions rather
 * than single biomes (one swamp chunk doesn't make its own weather). Pure Java over a {@link NodeSampler}, so it can be
 * tested on made-up worlds.
 *
 * <ul>
 *   <li><b>Lattice:</b> one node every {@link #SPACING} blocks, each the climate of the surface biome there.</li>
 *   <li><b>Smoothing:</b> each node's regional value is a Gaussian-weighted average of the nodes within
 *       {@link #RADIUS} of it (sigma {@link #SIGMA} nodes), about a kilometre across in all.</li>
 *   <li><b>Between nodes:</b> bilinear, so the regional field has no steps.</li>
 * </ul>
 * Raw and smoothed nodes are cached (least recently used first out), so a map or many queries in one area sample each
 * biome once. Not thread-safe: one per level, used from the server thread. Re-entrant, though: asking the world for a
 * biome can make the server run other queued tasks while it waits, and those may read this cache too, so lookups use
 * plain get-then-put rather than {@code computeIfAbsent} (which throws if the map changes during the computation).
 */
public final class ClimateField {

    /** Blocks between lattice nodes. */
    public static final int SPACING = 128;
    /** Smoothing reach, nodes. */
    public static final int RADIUS = 4;
    /** Smoothing width, nodes. */
    public static final double SIGMA = 2.0;
    /** Nodes kept in each cache. */
    static final int CACHE = 1 << 16;

    /** The climate at a lattice node (world position {@code nodeX * SPACING}, {@code nodeZ * SPACING}). */
    @FunctionalInterface
    public interface NodeSampler {
        BiomeClimate at(int nodeX, int nodeZ);
    }

    /** A smoothed regional climate. */
    public record Regional(double mean, double swing, double humidity) {
    }

    private static final double[] WEIGHTS = weights();

    private final NodeSampler sampler;
    private final Map<Long, BiomeClimate> raw = lru();
    private final Map<Long, Regional> smoothed = lru();

    public ClimateField(NodeSampler sampler) {
        this.sampler = sampler;
    }

    /** The raw climate at node ({@code ix}, {@code iz}). */
    public BiomeClimate node(int ix, int iz) {
        Long k = key(ix, iz);
        BiomeClimate c = raw.get(k);
        if (c == null) {
            c = sampler.at(ix, iz);
            raw.put(k, c);
        }
        return c;
    }

    /** The smoothed climate at node ({@code ix}, {@code iz}). */
    public Regional smoothedNode(int ix, int iz) {
        Long k = key(ix, iz);
        Regional r = smoothed.get(k);
        if (r == null) {
            r = smooth(ix, iz);
            smoothed.put(k, r);
        }
        return r;
    }

    /** The regional climate at world (x, z). */
    public Regional regional(double x, double z) {
        double gx = x / SPACING;
        double gz = z / SPACING;
        int ix = (int) Math.floor(gx);
        int iz = (int) Math.floor(gz);
        double fx = gx - ix;
        double fz = gz - iz;
        Regional a = smoothedNode(ix, iz);
        Regional b = smoothedNode(ix + 1, iz);
        Regional c = smoothedNode(ix, iz + 1);
        Regional d = smoothedNode(ix + 1, iz + 1);
        double wa = (1 - fx) * (1 - fz), wb = fx * (1 - fz), wc = (1 - fx) * fz, wd = fx * fz;
        return new Regional(
                a.mean * wa + b.mean * wb + c.mean * wc + d.mean * wd,
                a.swing * wa + b.swing * wb + c.swing * wc + d.swing * wd,
                a.humidity * wa + b.humidity * wb + c.humidity * wc + d.humidity * wd);
    }

    /** The nearest node's raw climate to world (x, z). */
    public BiomeClimate nearest(double x, double z) {
        return node((int) Math.round(x / SPACING), (int) Math.round(z / SPACING));
    }

    public void clear() {
        raw.clear();
        smoothed.clear();
    }

    private Regional smooth(int ix, int iz) {
        double mean = 0, swing = 0, humidity = 0, total = 0;
        int side = 2 * RADIUS + 1;
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                double w = WEIGHTS[(dz + RADIUS) * side + dx + RADIUS];
                if (w <= 0) {
                    continue;
                }
                BiomeClimate c = node(ix + dx, iz + dz);
                mean += c.mean() * w;
                swing += c.swing() * w;
                humidity += c.humidity() * w;
                total += w;
            }
        }
        return new Regional(mean / total, swing / total, humidity / total);
    }

    private static double[] weights() {
        int side = 2 * RADIUS + 1;
        double[] w = new double[side * side];
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                double d2 = dx * dx + dz * dz;
                w[(dz + RADIUS) * side + dx + RADIUS] = d2 > (RADIUS + 0.5) * (RADIUS + 0.5)
                        ? 0 : Math.exp(-d2 / (2 * SIGMA * SIGMA));
            }
        }
        return w;
    }

    private static long key(int ix, int iz) {
        return ((long) ix << 32) | (iz & 0xFFFFFFFFL);
    }

    private static <V> Map<Long, V> lru() {
        return new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > CACHE;
            }
        };
    }
}
