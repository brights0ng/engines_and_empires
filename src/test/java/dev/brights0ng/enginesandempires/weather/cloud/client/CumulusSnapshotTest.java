package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.io.File;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;

/**
 * Renders the sample cumulus (the same as {@code /eae weather clouds samples}) to PNGs in build/cloud-pictures, for
 * checking by eye. Always passes; prints build times.
 */
class CumulusSnapshotTest {

    static {
        CloudVoxelizer.seamOverlap = 0;
    }

    static CloudShape sample(CloudType t, double size, double thickness, int seed, double time) {
        CloudType.Look look = t.look;
        float r = (float) (t.radiusBlocks() * size);
        float thick = SimCloud.heapThickness(thickness, size);
        return new CloudShape(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 0, 0, 0, 0, 0, r, 200,
                200 + thick, look.density(), t.look.coverage(), look.edgeSoftness(), 1, 0, 0, t.id, look.tower(),
                look.anvil(), look.baseDarkness(), look.stormDarkness(), 0, 0, seed);
    }

    @Test
    void pictures() throws Exception {
        // The lighting's parts on their own (a typical mediocris, sunlit side).
        File parts = new File("build/cloud-pictures/parts");
        String[] names = {"normal_only", "with_sky", "with_ao", "with_sun"};
        boolean[][] flags = {{true, true, true}, {false, true, true}, {false, true, false}, {false, false, false}};
        for (int p = 0; p < names.length; p++) {
            CloudVoxelizer.debugNoSky = flags[p][0];
            CloudVoxelizer.debugNoSun = flags[p][1];
            CloudVoxelizer.debugNoAo = flags[p][2];
            CloudShape c = sample(CloudType.CUMULUS_CONGESTUS, 0.6, 900, 3, 0);
            CloudField f = CloudField.of(CloudFormation.of(c.regionId(), List.of(c)));
            CloudSnapshot snap = new CloudSnapshot();
            long t0 = System.nanoTime();
            CloudVoxelizer.build(f, 4, 0, snap.sink());
            System.out.printf("congestus 1.0, %s: %d ms%n", names[p], (System.nanoTime() - t0) / 1_000_000);
            snap.write(new File(parts, names[p] + ".png"), 320, 205, -12);
            CloudShape cm = sample(CloudType.CUMULUS_MEDIOCRIS, 1.0, 280, 3, 0);
            CloudSnapshot sm = new CloudSnapshot();
            CloudVoxelizer.build(CloudField.of(CloudFormation.of(cm.regionId(), List.of(cm))), 4, 0, sm.sink());
            sm.write(new File(parts, "mediocris_" + names[p] + ".png"), 320, 205, -12);
            CloudShape ch = sample(CloudType.CUMULUS_HUMILIS, 1.0, 110, 3, 0);
            CloudSnapshot sh = new CloudSnapshot();
            CloudVoxelizer.build(CloudField.of(CloudFormation.of(ch.regionId(), List.of(ch))), 4, 0, sh.sink());
            sh.write(new File(parts, "humilis_" + names[p] + ".png"), 320, 205, -12);
        }
        CloudVoxelizer.debugNoSky = false;
        CloudVoxelizer.debugNoSun = false;
        CloudVoxelizer.debugNoAo = false;
        // The churn on its own: none, today's, and twice the size.
        double[][] noise = {{0, 1}, {1, 1}, {1, 2}};
        double ns = CloudTuning.noiseStrength;
        double nc = CloudTuning.noiseScale;
        for (double[] q : noise) {
            CloudTuning.noiseStrength = q[0];
            CloudTuning.noiseScale = q[1];
            CloudShape c = sample(CloudType.CUMULUS_MEDIOCRIS, 1.0, 280, 3, 0);
            CloudField f = CloudField.of(CloudFormation.of(c.regionId(), List.of(c)));
            CloudSnapshot snap = new CloudSnapshot();
            CloudVoxelizer.build(f, 4, 0, snap.sink());
            snap.write(new File(parts, "noise_" + q[0] + "_scale_" + q[1] + ".png"), 320, 205, -12);
        }
        CloudTuning.noiseStrength = ns;
        CloudTuning.noiseScale = nc;
        int defaultRun = CloudVoxelizer.HEAP_MAX_RUN;
        for (int run : new int[]{1, 3}) {
            CloudVoxelizer.HEAP_MAX_RUN = run;
            CloudShape ch = sample(CloudType.CUMULUS_HUMILIS, 1.0, 110, 3, 0);
            CloudSnapshot sh = new CloudSnapshot();
            CloudVoxelizer.Result rr = CloudVoxelizer.build(
                    CloudField.of(CloudFormation.of(ch.regionId(), List.of(ch))), 4, 0, sh.sink());
            System.out.printf("humilis run %d: %d quads%n", run, rr.quads());
            sh.write(new File(parts, "humilis_run_" + run + ".png"), 320, 205, -12);
        }
        CloudVoxelizer.HEAP_MAX_RUN = defaultRun;
        Object[][] cases = {
                {CloudType.CUMULUS_HUMILIS, 110.0}, {CloudType.CUMULUS_MEDIOCRIS, 280.0},
                {CloudType.CUMULUS_CONGESTUS, 900.0}};
        File dir = new File("build/cloud-pictures");
        for (Object[] k : cases) {
            CloudType t = (CloudType) k[0];
            for (double size : new double[]{0.5, 1.0, 1.6}) {
                CloudShape c = sample(t, size, (Double) k[1], 3, 0);
                CloudField f = CloudField.of(CloudFormation.of(c.regionId(), List.of(c)));
                CloudSnapshot snap = new CloudSnapshot();
                long t0 = System.nanoTime();
                // Congestus are now kilometres across: drawn at voxel 8, as they would be from a little way off.
                int voxel = t == CloudType.CUMULUS_CONGESTUS ? 8 : 4;
                CloudVoxelizer.Result r = CloudVoxelizer.build(f, voxel, 0, snap.sink());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("%s size %.1f: %d quads, %d ms%n", t.id, size, r.quads(), ms);
                // Into the light (the sun behind the cloud) and with it (the sunlit side).
                snap.write(new File(dir, t.id + "_" + size + ".png"), 320, 25, -12);
                snap.write(new File(dir, t.id + "_" + size + "_lit.png"), 320, 205, -12);
            }
        }
    }
}
