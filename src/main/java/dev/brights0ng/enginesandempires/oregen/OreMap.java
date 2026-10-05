package dev.brights0ng.enginesandempires.oregen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Where the deposits of one ore type are.
 *
 * <p>The map is a smooth noise field whose feature size follows the layer's scale ("S"). Every peak
 * of that field is a deposit centre. Nothing is stored or generated ahead of time: the field is a
 * pure function of (world seed, ore id, x, z), so any part of the world can be asked about at any
 * time, including chunks that have never been generated, and the answer never changes.
 *
 * <p>How peaks are found: the field is sampled on a coarse lattice (eight nodes per S). A node is a
 * peak if it beats every node within two steps of it. Because that test only looks at a small
 * neighbourhood, the answer for a given peak does not depend on which region you asked about, which
 * is what lets worldgen ask one chunk at a time. The peak's position is then refined between lattice
 * nodes with a parabola fit, so deposits do not sit on a visible grid.
 *
 * <p>Every peak is a candidate. An {@link Abundance} can thin candidates out by location; each one has
 * a fixed random roll, so thinning never moves anything, it only removes.
 *
 * <p>Instances are immutable and thread-safe. Nothing here touches Minecraft.
 */
public final class OreMap {

    /** Lattice nodes per S. */
    private static final int LATTICE_DIV = 8;

    /** A node is a peak only if it beats every node within this many lattice steps. */
    private static final int PEAK_RADIUS = 2;

    /**
     * Noise wavelength per unit of S, calibrated so that, with every candidate kept, there is about one
     * deposit per S x S blocks.
     */
    private static final double WAVELENGTH_FACTOR = 1.243;

    /** Upper bound on lattice nodes touched by one query, to keep a bad query from eating memory. */
    private static final long MAX_NODES = 4_000_000L;

    private final OreLayer layer;
    private final Abundance abundance;
    private final long layerSeed;
    private final SimplexNoise2D noise;
    private final double step;
    private final double frequency;

    public OreMap(long worldSeed, OreLayer layer, Abundance abundance) {
        this.layer = Objects.requireNonNull(layer, "layer");
        this.abundance = Objects.requireNonNull(abundance, "abundance");
        this.layerSeed = SimplexNoise2D.mix64(worldSeed ^ ((long) layer.id().hashCode() * 0x9E3779B97F4A7C15L));
        this.noise = new SimplexNoise2D(layerSeed);
        this.step = layer.scale() / (double) LATTICE_DIV;
        this.frequency = 1.0 / (layer.scale() * WAVELENGTH_FACTOR);
    }

    public OreMap(long worldSeed, OreLayer layer) {
        this(worldSeed, layer, Abundance.FULL);
    }

    public OreLayer layer() {
        return layer;
    }

    /** Every deposit whose centre lies inside the box (bounds inclusive). */
    public List<Deposit> depositsInBox(int minX, int minZ, int maxX, int maxZ) {
        if (maxX < minX || maxZ < minZ) {
            return List.of();
        }

        // A refined peak can sit up to half a step from its node, so pad the candidate range by one node.
        int firstI = (int) Math.floor(minX / step) - 1;
        int lastI = (int) Math.ceil(maxX / step) + 1;
        int firstJ = (int) Math.floor(minZ / step) - 1;
        int lastJ = (int) Math.ceil(maxZ / step) + 1;

        // The peak test looks PEAK_RADIUS nodes out from every candidate, so sample that far beyond.
        int gridI = firstI - PEAK_RADIUS;
        int gridJ = firstJ - PEAK_RADIUS;
        int width = (lastI - firstI + 1) + 2 * PEAK_RADIUS;
        int height = (lastJ - firstJ + 1) + 2 * PEAK_RADIUS;
        if ((long) width * height > MAX_NODES) {
            throw new IllegalArgumentException("Query box is too large for this ore's scale");
        }

        double[] values = new double[width * height];
        for (int a = 0; a < width; a++) {
            for (int b = 0; b < height; b++) {
                values[a * height + b] = field((gridI + a) * step, (gridJ + b) * step);
            }
        }

        List<Deposit> found = new ArrayList<>();
        for (int i = firstI; i <= lastI; i++) {
            for (int j = firstJ; j <= lastJ; j++) {
                int index = (i - gridI) * height + (j - gridJ);
                if (!isPeak(values, index, height)) {
                    continue;
                }

                double center = values[index];
                double offsetX = parabolaOffset(values[index - height], center, values[index + height]);
                double offsetZ = parabolaOffset(values[index - 1], center, values[index + 1]);
                double peakX = (i + offsetX) * step;
                double peakZ = (j + offsetZ) * step;

                int x = (int) Math.round(peakX);
                int z = (int) Math.round(peakZ);
                if (x < minX || x > maxX || z < minZ || z > maxZ) {
                    continue;
                }

                long seed = SimplexNoise2D.mix64(layerSeed
                        ^ ((long) x * 0xD1B54A32D192ED03L)
                        ^ ((long) z * 0x8CB92BA72F3D8DD7L));
                if (!(roll(seed) < abundance.at(x, z))) {
                    continue; // thinned out here (a NaN answer also lands here)
                }
                found.add(new Deposit(layer.id(), x, z, seed));
            }
        }
        return List.copyOf(found);
    }

    /** Every deposit within {@code radius} blocks of (x, z), nearest first. */
    public List<Deposit> depositsNear(int x, int z, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        long radiusSquared = (long) radius * radius;
        List<Deposit> inRange = new ArrayList<>();
        for (Deposit deposit : depositsInBox(x - radius, z - radius, x + radius, z + radius)) {
            if (distanceSquared(deposit, x, z) <= radiusSquared) {
                inRange.add(deposit);
            }
        }
        inRange.sort(Comparator
                .comparingLong((Deposit d) -> distanceSquared(d, x, z))
                .thenComparingInt(Deposit::x)
                .thenComparingInt(Deposit::z));
        return List.copyOf(inRange);
    }

    /** The raw noise field at a block position. Package-private for the preview and tests. */
    double field(double x, double z) {
        return noise.noise(x * frequency, z * frequency);
    }

    /** A fixed per-deposit number in [0, 1), independent of the seed's other uses. */
    private static double roll(long depositSeed) {
        return (SimplexNoise2D.mix64(depositSeed ^ 0xA0761D6478BD642FL) >>> 11) * 0x1.0p-53;
    }

    private static long distanceSquared(Deposit d, int x, int z) {
        long dx = (long) d.x() - x;
        long dz = (long) d.z() - z;
        return dx * dx + dz * dz;
    }

    /** True if the node at {@code index} beats every other node within PEAK_RADIUS steps. */
    private static boolean isPeak(double[] values, int index, int height) {
        double center = values[index];
        for (int di = -PEAK_RADIUS; di <= PEAK_RADIUS; di++) {
            for (int dj = -PEAK_RADIUS; dj <= PEAK_RADIUS; dj++) {
                if (di == 0 && dj == 0) {
                    continue;
                }
                double other = values[index + di * height + dj];
                if (other > center) {
                    return false;
                }
                // Exact ties are practically impossible, but break them the same way everywhere.
                if (other == center && (di > 0 || (di == 0 && dj > 0))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Where, between -0.5 and 0.5 steps from the centre node, a parabola through three samples peaks. */
    private static double parabolaOffset(double before, double center, double after) {
        double curvature = before - 2.0 * center + after;
        if (curvature >= -1.0e-12) {
            return 0.0;
        }
        double offset = 0.5 * (before - after) / curvature;
        return Math.max(-0.5, Math.min(0.5, offset));
    }
}
