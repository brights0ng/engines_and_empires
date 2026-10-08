package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Times each step of a cumulonimbus build (sampling, filling, meshing), printed to the test output. */
class CloudProfileTest {

    private static final UUID REGION = UUID.randomUUID();

    private static CloudShape c(int id, double x, double z, float r, float base, float top, float tower, int seed) {
        return new CloudShape(new UUID(0, id), REGION, "minecraft:overworld", x, z, 0.3, 0, 0,
                r, base, top, 0.8f, 0.88f, 0.5f, 1, 0, 0, "cumulonimbus_capillatus", tower, 0.8f, 0.4f, 0.82f, 0.8f, 0.6f,
                seed);
    }

    @Test
    void profileCumulonimbus() {
        CloudFormation f = CloudFormation.of(REGION, List.of(
                c(1, 0, 0, 260, 180, 520, 0.9f, 101),
                c(2, -220, 120, 180, 185, 380, 0.4f, 102),
                c(3, -180, -160, 170, 182, 360, 0.3f, 103),
                c(4, 160, -60, 150, 190, 330, 0.2f, 104)));
        for (int run = 0; run < 3; run++) {
            for (int s : new int[]{4, 8}) {
                long t0 = System.nanoTime();
                CloudField field = CloudField.of(f);
                CloudVoxelizer.Grid g = CloudVoxelizer.grid(field, s);
                long t1 = System.nanoTime();
                CloudVoxelizer.Cropped sampled = CloudVoxelizer.sample(field, g, s, 500 + run);
                long t2 = System.nanoTime();
                System.out.printf("  coarse %.1f ms, lattice %.1f ms (%d density points, near cells %d of %d), "
                                + "fill %.1f ms%n", CloudVoxelizer.tCoarse / 1e6, CloudVoxelizer.tLattice / 1e6,
                        CloudVoxelizer.densityPoints, CloudVoxelizer.nearCells, CloudVoxelizer.totalCells,
                        CloudVoxelizer.tFill / 1e6);
                CloudVoxelizer.Cropped cropped = CloudVoxelizer.crop(sampled.solid(), sampled.grid());
                int[] n = new int[1];
                CloudVoxelizer.Result r = CloudVoxelizer.mesh(cropped.solid(), cropped.grid(), s, field,
                        (x, y, z, argb) -> n[0]++);
                long t3 = System.nanoTime();
                int filled = 0;
                for (byte b : sampled.solid()) {
                    if (b != 0) {
                        filled++;
                    }
                }
                System.out.printf("run %d voxel %d: grid %dx%dx%d (%.1fM voxels, sampled %.1fM, %d solid, cropped %.1fM) "
                                + "field %.1f ms, sample %.1f ms, crop+mesh %.1f ms, %d quads%n",
                        run, s, g.nx(), g.ny(), g.nz(), g.voxels() / 1e6, sampled.grid().voxels() / 1e6, filled,
                        cropped.grid().voxels() / 1e6,
                        (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, r.quads());
            }
        }
    }
}
