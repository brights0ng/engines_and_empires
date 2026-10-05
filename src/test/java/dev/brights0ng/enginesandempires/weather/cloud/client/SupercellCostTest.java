package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;

/**
 * What one whole-storm rebuild of the design's average supercell costs with Bright's experimental render settings
 * (voxel 2, draw distance 8,192). Printed, for comparing against the time it takes in game.
 */
class SupercellCostTest {

    @Test
    void wholeRebuildAtVoxelTwo() {
        double variety = CloudTuning.variety;
        CloudTuning.variety = 0;
        try {
            CloudFormation f = SupercellTest.storm(1, 0);
            CloudField probe = CloudField.of(f);
            Supercell s = probe.storm;
            for (double[] cam : new double[][]{{s.ffX, s.ffZ}, {s.ux - 3000, s.uz}}) {
                List<CloudMeshes.Planned> plan = CloudMeshes.plan(probe.bounds(), cam[0], CloudScale.GROUND_Y + 2,
                        cam[1], CloudTuning.sectionSize, 2, 8192);
                CloudVoxelizer.buildSection(CloudField.of(f), 8, 0, 0, 0, 0, CloudTuning.sectionSize,
                        (x, y, z, c) -> { });
                long total = 0, slowest = 0;
                int quads = 0, withCloud = 0;
                java.util.Map<Integer, long[]> byVoxel = new java.util.TreeMap<>();
                for (CloudMeshes.Planned p : plan) {
                    long t0 = System.nanoTime();
                    CloudVoxelizer.Result r = CloudVoxelizer.buildSection(CloudField.of(f), p.voxel(), 1234, p.sx(),
                            p.sy(), p.sz(), CloudTuning.sectionSize, (x, y, z, c) -> { });
                    long dt = System.nanoTime() - t0;
                    total += dt;
                    slowest = Math.max(slowest, dt);
                    quads += r.quads();
                    withCloud += r.isEmpty() ? 0 : 1;
                    long[] v = byVoxel.computeIfAbsent(p.voxel(), k -> new long[2]);
                    v[0]++;
                    v[1] += dt;
                }
                System.out.printf("Voxel 2, draw 8192, camera %s: %d sections (%d with cloud), %d quads, whole rebuild"
                                + " %d ms of CPU = %.1f s at half a core, slowest section %d ms%n",
                        cam[0] == s.ffX ? "under the forward flank" : "3,000 blocks upwind", plan.size(), withCloud,
                        quads, total / 1_000_000, total / 1e9 / 0.5, slowest / 1_000_000);
                for (java.util.Map.Entry<Integer, long[]> e : byVoxel.entrySet()) {
                    System.out.printf("  voxel %d: %d sections, %d ms%n", e.getKey(), e.getValue()[0],
                            e.getValue()[1] / 1_000_000);
                }
                assertTrue(total > 0);
            }
        } finally {
            CloudTuning.variety = variety;
        }
    }
}
