package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;

/**
 * 2026-10-06, Bright: cumulus looked like lumps of dough, round and all alike. Sizes now vary (many small, few big),
 * each cumulus is a cluster of bubbles with its own outline, bases are flat, and nearby clouds get small round puffs.
 */
class CumulusShapeTest {

    static {
        CloudVoxelizer.seamOverlap = 0;
    }

    private static final UUID REGION = UUID.randomUUID();

    /** A mature cumulus of type {@code type}, radius {@code r}, base y 200, {@code thick} blocks thick. */
    static CloudShape cumulus(String type, float r, float thick, int seed) {
        return new CloudShape(UUID.randomUUID(), REGION, "minecraft:overworld", 0, 0, 0, 0, 0,
                r, 200, 200 + thick, 0.8f, 0.8f, 0.5f, 1, 0, 0, type, 0, 0, 0.3f, 0, 0, 0, seed);
    }

    static CloudField field(CloudShape c) {
        return CloudField.of(CloudFormation.of(c.regionId(), List.of(c)));
    }

    /** The solid voxels of {@code field} at voxel size {@code s}. */
    static CloudVoxelizer.Cropped voxels(CloudField field, int s) {
        CloudVoxelizer.Grid g = CloudVoxelizer.grid(field, s);
        return CloudVoxelizer.sample(field, g, s, 0, null, 0, CloudVoxelizer.latticeK(field, s, true));
    }

    /** Per column (i + j * nx): the highest and lowest solid voxel (v), or -1. */
    static int[][] columns(CloudVoxelizer.Cropped c) {
        CloudVoxelizer.Grid g = c.grid();
        int[] top = new int[g.nx() * g.nz()];
        int[] bottom = new int[g.nx() * g.nz()];
        Arrays.fill(top, -1);
        Arrays.fill(bottom, -1);
        for (int v = 0; v < g.ny(); v++) {
            for (int j = 0; j < g.nz(); j++) {
                for (int i = 0; i < g.nx(); i++) {
                    if (c.solid()[(v * g.nz() + j) * g.nx() + i] != 0) {
                        int k = i + j * g.nx();
                        top[k] = v;
                        if (bottom[k] < 0) {
                            bottom[k] = v;
                        }
                    }
                }
            }
        }
        return new int[][]{top, bottom};
    }

    @Test
    void manySmallCumulusAndFewBig() {
        SplittableRandom rng = new SplittableRandom(5);
        int n = 20_000;
        double[] sizes = new double[n];
        double area = 0;
        int big = 0;
        for (int i = 0; i < n; i++) {
            double s = SimCloud.heapSize(CloudType.CUMULUS_HUMILIS, rng);
            assertTrue(s >= 0.25 - 1e-9 && s <= 2.5 + 1e-9, "size " + s);
            sizes[i] = s;
            area += s * s;
            if (s > 1.2) {
                big++;
            }
        }
        Arrays.sort(sizes);
        double median = sizes[n / 2];
        System.out.printf("cumulus sizes: median %.2f, mean area %.2f, %.1f%% bigger than 1.2%n", median, area / n,
                100.0 * big / n);
        // 0.25-2.5 (Bright, 2026-10-07: wider both ways).
        assertEquals(0.45, median, 0.04);
        assertEquals(0.63, area / n, 0.04, "sky cover about 0.63 of the old typical size at the same count");
        assertTrue(big < 0.15 * n, "few big ones");
        // Small cumulus are shallow; big ones a little taller for their width.
        assertTrue(SimCloud.heapThickness(100, 0.5) < 70 && SimCloud.heapThickness(100, 1.6) > 130);
    }

    @Test
    void everyCumulusHasItsOwnLumpyOutline() {
        CloudVoxelizer.Cropped a = voxels(field(cumulus("cumulus_humilis", 80, 110, 1)), 4);
        CloudVoxelizer.Cropped b = voxels(field(cumulus("cumulus_humilis", 80, 110, 2)), 4);
        int[] ta = columns(a)[0];
        int[] tb = columns(b)[0];
        // Lumps: separate local tops (plateaus no lower than any neighbour). A smooth dome has one.
        int peaksA = peaks(ta, a.grid().nx(), a.grid().nz());
        int peaksB = peaks(tb, b.grid().nx(), b.grid().nz());
        System.out.printf("humilis tops: %d and %d bumps; grids %dx%d and %dx%d%n", peaksA, peaksB, a.grid().nx(),
                a.grid().nz(), b.grid().nx(), b.grid().nz());
        assertTrue(peaksA >= 2 && peaksB >= 2, "several lumps on top, not one dome");
        assertTrue(a.grid().nx() != b.grid().nx() || a.grid().nz() != b.grid().nz()
                || !Arrays.equals(ta, tb), "two seeds, two shapes");
    }

    private static int peaks(int[] top, int nx, int nz) {
        boolean[] max = new boolean[nx * nz];
        for (int j = 1; j < nz - 1; j++) {
            for (int i = 1; i < nx - 1; i++) {
                int h = top[i + j * nx];
                if (h < 0) {
                    continue;
                }
                boolean peak = true;
                for (int dj = -1; dj <= 1 && peak; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (top[i + di + (j + dj) * nx] > h) {
                            peak = false;
                            break;
                        }
                    }
                }
                max[i + j * nx] = peak;
            }
        }
        // Count connected plateaus of local maxima, ignoring one-column specks.
        int n = 0;
        boolean[] seen = new boolean[nx * nz];
        int[] stack = new int[nx * nz];
        for (int s = 0; s < max.length; s++) {
            if (!max[s] || seen[s]) {
                continue;
            }
            int sp = 0, size = 0;
            stack[sp++] = s;
            seen[s] = true;
            while (sp > 0) {
                int p = stack[--sp];
                size++;
                int pi = p % nx, pj = p / nx;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int qi = pi + di, qj = pj + dj;
                        if (qi < 0 || qj < 0 || qi >= nx || qj >= nz) {
                            continue;
                        }
                        int q = qi + qj * nx;
                        if (max[q] && !seen[q] && top[q] == top[p]) {
                            seen[q] = true;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            if (size >= 2) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aCumulusSitsOnAFlatBase() {
        CloudField f = field(cumulus("cumulus_mediocris", 140, 260, 3));
        CloudVoxelizer.Cropped c = voxels(f, 4);
        int[] bottom = columns(c)[1];
        // Under the middle of the cloud (away from its rounded, frayed edges), the base is level.
        int nx = c.grid().nx(), nz = c.grid().nz();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, counted = 0;
        for (int j = 2 * nz / 5; j < 3 * nz / 5; j++) {
            for (int i = 2 * nx / 5; i < 3 * nx / 5; i++) {
                int b = bottom[i + j * nx];
                if (b >= 0) {
                    lo = Math.min(lo, b);
                    hi = Math.max(hi, b);
                    counted++;
                }
            }
        }
        System.out.printf("mediocris base: %d columns, lowest to highest base %d voxels%n", counted, hi - lo);
        assertTrue(counted > 50);
        assertTrue(hi - lo <= 2, "a flat base: within two voxels (8 blocks) under the middle");
    }

    /** 2026-10-07, Bright: the smallest bubbles stuck out like boils; sizes now stay within a narrow range. */
    @Test
    void bubblesStayWithinAModestSizeRange() {
        for (String[] k : new String[][]{{"cumulus_humilis", "80", "110"}, {"cumulus_mediocris", "140", "260"},
                {"cumulus_congestus", "600", "900"}}) {
            CloudField f = field(cumulus(k[0], Float.parseFloat(k[1]), Float.parseFloat(k[2]), 4));
            CloudField.Member m = f.members.getFirst();
            double min = Double.MAX_VALUE, max = 0;
            for (int b = 0; b < m.cu.n; b++) {
                min = Math.min(min, m.cu.r[b]);
                max = Math.max(max, m.cu.r[b]);
            }
            System.out.printf("%s (radius %.0f): %d bubbles, %.0f to %.0f blocks%n", k[0], m.r, m.cu.n, min, max);
            assertTrue(min >= m.r * Cumulus.minShare(m.r) - 1e-6, "none below the floor");
            assertTrue(max / min <= 4.5, "a narrow range: " + max / min);
        }
    }

    /** 2026-10-07, Bright: congestus are usually tall and wide; a sample one is wider than tall. */
    @Test
    void congestusAreBroad() {
        CloudVoxelizer.Cropped c = voxels(field(cumulus("cumulus_congestus", 600, 900, 5)), 8);
        CloudVoxelizer.Grid g = c.grid();
        int top = -1, bottom = Integer.MAX_VALUE, minI = Integer.MAX_VALUE, maxI = -1;
        for (int v = 0; v < g.ny(); v++) {
            for (int j = 0; j < g.nz(); j++) {
                for (int i = 0; i < g.nx(); i++) {
                    if (c.solid()[(v * g.nz() + j) * g.nx() + i] != 0) {
                        top = Math.max(top, v);
                        bottom = Math.min(bottom, v);
                        minI = Math.min(minI, i);
                        maxI = Math.max(maxI, i);
                    }
                }
            }
        }
        double width = (maxI - minI + 1) * 8.0, height = (top - bottom + 1) * 8.0;
        System.out.printf("congestus: %.0f wide, %.0f tall (%.1fx)%n", width, height, width / height);
        assertTrue(width > height, "wider than tall");
    }
    @Test
    void layerCloudsAreUntouched() {
        CloudShape s = new CloudShape(UUID.randomUUID(), REGION, "minecraft:overworld", 0, 0, 0, 0, 0, 300, 150, 200,
                0.8f, 0.8f, 0.5f, 1, 0, 0, "stratocumulus", 0, 0, 0.3f, 0, 0, 0, 7);
        CloudField f = field(s);
        assertTrue(!f.heap && f.totalBubbles == 0, "no bubbles or flat base on layer clouds");
    }
}
