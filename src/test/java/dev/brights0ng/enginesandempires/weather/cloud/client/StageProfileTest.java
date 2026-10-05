package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;

/** Where a whole-storm rebuild's time goes, by stage and voxel size (voxel 2, draw 8192, under the forward flank). */
class StageProfileTest {

    @Test
    void stages() {
        CloudTuning.variety = 0;
        try {
            CloudFormation f = SupercellTest.storm(1, 0);
            CloudField probe = CloudField.of(f);
            Supercell s = probe.storm;
            List<CloudMeshes.Planned> plan = CloudMeshes.plan(probe.bounds(), s.ffX, CloudScale.GROUND_Y + 2, s.ffZ,
                    CloudTuning.sectionSize, 2, 8192);
            for (int warm = 0; warm < 2; warm++) {
                for (int k = 0; k < Math.min(40, plan.size()); k++) {
                    CloudMeshes.Planned p = plan.get(k);
                    CloudVoxelizer.buildSection(CloudField.of(f), p.voxel(), 0, p.sx(), p.sy(), p.sz(),
                            CloudTuning.sectionSize, (x, y, z, c) -> { });
                }
            }
            java.util.Map<Integer, long[]> by = new java.util.TreeMap<>();
            long field = 0;
            for (CloudMeshes.Planned p : plan) {
                CloudVoxelizer.tCoarse = 0;
                CloudVoxelizer.tLattice = 0;
                CloudVoxelizer.tFill = 0;
                CloudVoxelizer.densityPoints = 0;
                long t0 = System.nanoTime();
                CloudField cf = CloudField.of(f);
                long t1 = System.nanoTime();
                CloudVoxelizer.Result r = CloudVoxelizer.buildSection(cf, p.voxel(), 1234, p.sx(), p.sy(), p.sz(),
                        CloudTuning.sectionSize, (x, y, z, c) -> { });
                long t2 = System.nanoTime();
                field += t1 - t0;
                long[] v = by.computeIfAbsent(p.voxel(), k -> new long[7]);
                long sampled = r.isEmpty() && CloudVoxelizer.tLattice == 0 ? 0 : 1;
                v[0]++;
                v[1] += CloudVoxelizer.tCoarse;
                v[2] += CloudVoxelizer.tLattice;
                v[3] += CloudVoxelizer.tFill;
                v[4] += (t2 - t1);
                v[5] += CloudVoxelizer.densityPoints;
                v[6] += r.quads();
            }
            System.out.printf("PROFILE field construction total %d ms%n", field / 1_000_000);
            for (java.util.Map.Entry<Integer, long[]> e : by.entrySet()) {
                long[] v = e.getValue();
                long rest = v[4] - v[1] - v[2] - v[3];
                System.out.printf("PROFILE voxel %d: %d sections, total %d ms = coarse %d + lattice (noise) %d + fill %d"
                                + " + crop/mesh/emit %d; %d density points, %d quads%n", e.getKey(), v[0],
                        v[4] / 1_000_000, v[1] / 1_000_000, v[2] / 1_000_000, v[3] / 1_000_000, rest / 1_000_000,
                        v[5], v[6]);
            }
        } finally {
            CloudTuning.variety = 1;
        }
    }
}
