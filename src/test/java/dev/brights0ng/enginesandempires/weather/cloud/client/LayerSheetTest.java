package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;

/** Layer clouds drawn as continuous sheets (layered clouds, stage 1, 2026-10-07). */
class LayerSheetTest {

    /** A mature layer cloud of type {@code t} in the slot centred at (x, z), as the spawner lays one out. */
    static SimCloud layer(CloudType t, double x, double z, long seed, double vx) {
        SplittableRandom rng = new SplittableRandom(seed);
        double thick = CloudScale.thickness(t, t.baseMin, 0.5);
        return new SimCloud(new UUID(seed, seed * 31), t, x, z, vx, 0, 0, -1_000_000, 1_000_000,
                (float) CloudScale.baseY(t.baseMin + 200), (float) thick, t.look.coverage(), 0, 0,
                SimCloud.domesFor(t, 0.6 * 1536, rng), 0, false);
    }

    static Map<UUID, List<CloudShape>> regions(List<SimCloud> clouds, double now) {
        Map<UUID, List<CloudShape>> by = new LinkedHashMap<>();
        for (SimCloud c : clouds) {
            for (CloudShape s : c.shapes("minecraft:overworld", now)) {
                by.computeIfAbsent(s.regionId(), k -> new ArrayList<>()).add(s);
            }
        }
        return by;
    }

    @Test
    void touchingLayerCloudsOfADeckMergeIntoOneSheet() {
        List<SimCloud> clouds = new ArrayList<>();
        // A 3 x 2 block of stratocumulus slots, one stratus far off, and a cumulus among them.
        long seed = 1;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 2; j++) {
                clouds.add(layer(CloudType.STRATOCUMULUS, i * 1536, j * 1536, seed++, 0.1 + 0.01 * i));
            }
        }
        clouds.add(layer(CloudType.STRATUS, 20_000, 0, seed++, 0.1));
        List<CloudFormation> f = CloudTracker.group(regions(clouds, 0), 0);
        System.out.printf("%d clouds -> %d formations%n", clouds.size(), f.size());
        assertEquals(2, f.size(), "the six stratocumulus are one sheet; the stratus is apart");
        // Offsets are taken at one time, though the clouds move at different speeds.
        List<CloudFormation> later = CloudTracker.group(regions(clouds, 1200), 1200);
        CloudFormation sheet = later.stream().filter(x -> x.members().size() > 5).findFirst().orElseThrow();
        for (CloudShape m : sheet.members()) {
            assertEquals(m.xAt(1200) - sheet.anchor().xAt(1200), sheet.offsetX(m), 1e-6);
        }
    }

    @Test
    void pictures() throws Exception {
        File dir = new File("build/cloud-pictures/layers");
        CloudType[] types = {CloudType.STRATUS, CloudType.STRATOCUMULUS, CloudType.NIMBOSTRATUS, CloudType.ALTOSTRATUS};
        for (CloudType t : types) {
            List<SimCloud> clouds = new ArrayList<>();
            long seed = 11;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    if (i == 2 && j == 2) {
                        continue;
                    }
                    clouds.add(layer(t, i * 1536, j * 1536, seed++, 0.1));
                }
            }
            List<CloudFormation> f = CloudTracker.group(regions(clouds, 0), 0);
            CloudFormation sheet = f.getFirst();
            CloudField field = CloudField.of(sheet);
            CloudSnapshot snap = new CloudSnapshot();
            long t0 = System.nanoTime();
            CloudVoxelizer.Result r = CloudVoxelizer.build(field, 16, 0, snap.sink());
            System.out.printf("%s sheet: %d formations, %d quads, %d ms%n", t.id, f.size(), r.quads(),
                    (System.nanoTime() - t0) / 1_000_000);
            assertTrue(f.size() == 1, "one sheet");
            snap.write(new File(dir, t.id + "_sheet.png"), 900, 205, -25);
        }
    }

    /**
     * Nearer views of part of a sheet (2026-10-07 evening: puffier layer clouds, shaded by their own shape): from
     * above and from below, at a finer voxel size, cropped to a square around the middle of a two-slot sheet.
     */
    @Test
    void closeUps() throws Exception {
        File dir = new File("build/cloud-pictures/layers");
        CloudType[] types = {CloudType.STRATUS, CloudType.STRATOCUMULUS, CloudType.NIMBOSTRATUS, CloudType.ALTOSTRATUS};
        int[] voxel = {4, 8, 16, 8};
        double[] half = {500, 900, 1400, 1300};
        for (int ti = 0; ti < types.length; ti++) {
            CloudType t = types[ti];
            List<SimCloud> clouds = new ArrayList<>();
            clouds.add(layer(t, 0, 0, 41, 0.1));
            clouds.add(layer(t, 1536, 0, 42, 0.1));
            CloudFormation sheet = CloudTracker.group(regions(clouds, 0), 0).getFirst();
            CloudField field = CloudField.of(sheet);
            // The middle of the two clouds, in the meshes' (anchor-local) frame.
            double cx = 0, cz = 0;
            for (CloudShape m : sheet.members()) {
                cx += sheet.offsetX(m) / sheet.members().size();
                cz += sheet.offsetZ(m) / sheet.members().size();
            }
            double ccx = cx, ccz = cz;
            String[] names = {"_above.png", "_below.png", "_above_other_side.png"};
            double[] yaws = {205, 205, 25};
            double[] pitches = {-30, 20, -30};
            for (int view = 0; view < 3; view++) {
                CloudSnapshot snap = new CloudSnapshot();
                CloudVoxelizer.VertexSink inner = snap.sink();
                float[] quad = new float[12];
                float[] nq = new float[12];
                int[] col = new int[4];
                int[] n = {0};
                double h = half[ti];
                CloudVoxelizer.VertexSink crop = new CloudVoxelizer.VertexSink() {
                    @Override
                    public void vertex(float x, float y, float z, int argb) {
                        vertex(x, y, z, argb, 0, 1, 0);
                    }

                    @Override
                    public void vertex(float x, float y, float z, int argb, float nx, float ny, float nz) {
                        int k = n[0];
                        quad[k * 3] = x;
                        quad[k * 3 + 1] = y;
                        quad[k * 3 + 2] = z;
                        nq[k * 3] = nx;
                        nq[k * 3 + 1] = ny;
                        nq[k * 3 + 2] = nz;
                        col[k] = argb;
                        if (++n[0] == 4) {
                            n[0] = 0;
                            for (int q = 0; q < 4; q++) {
                                if (Math.abs(quad[q * 3] - ccx) > h || Math.abs(quad[q * 3 + 2] - ccz) > h) {
                                    return;
                                }
                            }
                            for (int q = 0; q < 4; q++) {
                                inner.vertex(quad[q * 3], quad[q * 3 + 1], quad[q * 3 + 2], col[q], nq[q * 3],
                                        nq[q * 3 + 1], nq[q * 3 + 2]);
                            }
                        }
                    }
                };
                long t0 = System.nanoTime();
                CloudVoxelizer.Result r = CloudVoxelizer.build(field, voxel[ti], 0, crop);
                System.out.printf("%s close-up (%s): voxel %d, %d quads in all, %d ms%n", t.id, names[view],
                        voxel[ti], r.quads(), (System.nanoTime() - t0) / 1_000_000);
                snap.write(new File(dir, t.id + names[view]), 900, yaws[view], pitches[view]);
            }
        }
    }

    /**
     * Layer sheets are shaded by their own shape (2026-10-07 evening): a nimbostratus's sunlit top is bright and its
     * lower flanks lie in its shadow; a stratocumulus's underside is lighter where it is thin.
     */
    @Test
    void layerSheetsShadeThemselves() {
        for (CloudType t : new CloudType[]{CloudType.NIMBOSTRATUS, CloudType.STRATOCUMULUS}) {
            List<SimCloud> clouds = new ArrayList<>();
            clouds.add(layer(t, 0, 0, 41, 0.1));
            clouds.add(layer(t, 1536, 0, 42, 0.1));
            CloudFormation sheet = CloudTracker.group(regions(clouds, 0), 0).getFirst();
            CloudField field = CloudField.of(sheet);
            List<float[]> verts = new ArrayList<>();
            // Lit as the shader would, by the build's light (CloudShading); kept: height, sky part, sun part.
            CloudVoxelizer.build(field, t == CloudType.NIMBOSTRATUS ? 16 : 8, 0, new CloudVoxelizer.VertexSink() {
                @Override
                public void vertex(float x, float y, float z, int argb) {
                    vertex(x, y, z, argb, 0, 1, 0);
                }

                @Override
                public void vertex(float x, float y, float z, int argb, float nx, float ny, float nz) {
                    double[] p = CloudShading.parts(argb, nx, ny, nz, field.lightX, field.lightY, field.lightZ, 1,
                            CloudTuning.shadowSide);
                    verts.add(new float[]{y, ((argb >> 16) & 255) / 255f, (float) p[1]});
                }
            });
            verts.sort((a, b) -> Float.compare(a[0], b[0]));
            int n = verts.size(), tenth = n / 10;
            double topSun = 0, lowSun = 0;
            for (int i = 0; i < tenth; i++) {
                lowSun += verts.get(i)[2] / tenth;
                topSun += verts.get(n - 1 - i)[2] / tenth;
            }
            double minSky = 1, maxSky = 0;
            // The underside: the lowest third (puff bottoms, thick, and the rims around them, thin).
            for (int i = 0; i < n / 3; i++) {
                minSky = Math.min(minSky, verts.get(i)[1]);
                maxSky = Math.max(maxSky, verts.get(i)[1]);
            }
            System.out.printf("%s: sun on the top tenth %.2f, the lowest tenth %.2f; sky on the lowest third %.2f-%.2f%n",
                    t.id, topSun, lowSun, minSky, maxSky);
            // (The sun's part is at most 1 - shadowSide, 0.35, on a surface facing it square on.)
            assertTrue(topSun > 0.12, "the top is sunlit");
            assertTrue(topSun > lowSun + 0.1, "the lower part is in the cloud's own shadow");
            if (t == CloudType.STRATOCUMULUS) {
                assertTrue(maxSky - minSky > 0.15, "thin parts of the underside are lighter than thick ones");
            }
        }
    }
}
