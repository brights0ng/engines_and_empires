package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The mesher's 2026-10-08 speed-ups (bubbles skipped when they can't matter, the base cut-off, reliefs worked out
 * lazily, cached puff cells, primitive caches) give exactly the same meshes: every vertex's position, colour and
 * normal, bit for bit, against the field worked out the old way ({@link CloudField#debugReference}). On a storm and a
 * stratocumulus deck always; on the sky saved in game ({@code run/cloud-dump.txt}) too when {@code EAE_BENCH=1}.
 */
class CloudEquivalenceTest {

    private static final UUID REGION = UUID.randomUUID();

    private static CloudShape dome(int id, String type, double x, double z, float r, float base, float top,
                                   float tower, float anvil, float coverage) {
        return new CloudShape(new UUID(0, id), REGION, "minecraft:overworld", x, z, 0.3, 0.1, 0, r, base, top, 0.8f,
                coverage, 0.5f, 1, 0, 0, type, tower, anvil, 0.4f, 0.82f, 0.8f, 0.6f, 101 + id,
                Float.NEGATIVE_INFINITY);
    }

    /** A checksum of every vertex built for formation {@code f} seen from (camX, camY, camZ), and how many. */
    private static long[] mesh(CloudFormation f, double camX, double camY, double camZ, double time, int voxel,
                               CloudShadows shadows) {
        CloudField bounds = CloudField.of(f);
        long[] out = {17, 0};
        if (bounds == null) {
            return out;
        }
        CloudLight light = CloudLight.bakeAt(0.15);
        int size = CloudTuning.sectionSize;
        for (CloudMeshes.Planned p : RebuildSchedule.plan(bounds.bounds(), camX, camY, camZ, size, voxel, 3072)) {
            CloudField field = CloudField.of(f);
            field.withLight(light.x(), light.y(), light.z(), light.strength());
            field.withShadows(shadows, f, time);
            boolean may = CloudVoxelizer.sectionMayHaveCloud(field, p.voxel(), time, p.sx(), p.sy(), p.sz(), size);
            out[0] = out[0] * 31 + (may ? 1 : 0);
            if (!may) {
                continue;
            }
            CloudVoxelizer.buildSection(field, p.voxel(), time, p.sx(), p.sy(), p.sz(), size,
                    new CloudVoxelizer.VertexSink() {
                        @Override
                        public void vertex(float x, float y, float z, int argb) {
                            vertex(x, y, z, argb, 0, 0, 0);
                        }

                        @Override
                        public void vertex(float x, float y, float z, int argb, float nx, float ny, float nz) {
                            long h = out[0];
                            for (int bits : new int[]{Float.floatToIntBits(x), Float.floatToIntBits(y),
                                    Float.floatToIntBits(z), argb, Float.floatToIntBits(nx),
                                    Float.floatToIntBits(ny), Float.floatToIntBits(nz)}) {
                                h = (h ^ bits) * 0x100000001B3L;
                            }
                            out[0] = h;
                            out[1]++;
                        }
                    });
        }
        return out;
    }

    private static void assertSame(CloudFormation f, double camX, double camY, double camZ, double time, int voxel,
                                   CloudShadows shadows, String what) {
        long[] fast = mesh(f, camX, camY, camZ, time, voxel, shadows);
        CloudField.debugReference = true;
        long[] reference;
        try {
            reference = mesh(f, camX, camY, camZ, time, voxel, shadows);
        } finally {
            CloudField.debugReference = false;
        }
        assertEquals(reference[1], fast[1], what + ": vertex count");
        assertEquals(reference[0], fast[0], what + ": every vertex bit for bit");
    }

    @Test
    void aStormMeshesExactlyAsBefore() {
        CloudFormation f = CloudFormation.of(REGION, List.of(
                dome(1, "cumulonimbus_capillatus", 0, 0, 260, 180, 520, 0.9f, 0.8f, 0.88f),
                dome(2, "cumulonimbus_capillatus", -220, 120, 180, 185, 380, 0.4f, 0.8f, 0.88f),
                dome(3, "cumulonimbus_capillatus", -180, -160, 170, 182, 360, 0.3f, 0.8f, 0.88f)), 0);
        assertSame(f, 300, 150, -200, 1000, 4, CloudShadows.EMPTY, "storm, voxel 4");
        assertSame(f, 900, 400, 900, 1000, 8, CloudShadows.EMPTY, "storm, voxel 8");
    }

    @Test
    void aCumulusFieldMeshesExactlyAsBefore() {
        CloudFormation f = CloudFormation.of(REGION, List.of(
                dome(1, "cumulus_mediocris", 0, 0, 90, 190, 300, 0.5f, 0, 0.8f),
                dome(2, "cumulus_mediocris", 120, 40, 70, 192, 270, 0.3f, 0, 0.8f)), 0);
        assertSame(f, 50, 180, -150, 500, 4, CloudShadows.EMPTY, "cumulus");
    }

    @Test
    void aStratocumulusDeckMeshesExactlyAsBefore() {
        List<CloudShape> domes = new ArrayList<>();
        int id = 1;
        for (int i = -3; i <= 3; i++) {
            for (int j = -3; j <= 3; j++) {
                domes.add(dome(id++, "stratocumulus", i * 260 + (j % 2) * 90, j * 240, 300, 170, 215, 0, 0, 0.75f));
            }
        }
        CloudFormation f = CloudFormation.of(REGION, domes, 0);
        assertSame(f, 100, 120, 80, 2000, 4, CloudShadows.EMPTY, "stratocumulus deck");
    }

    @Test
    void theSavedSkyMeshesExactlyAsBefore() throws Exception {
        Path dump = Path.of("run", "cloud-dump.txt");
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("EAE_BENCH")) && Files.exists(dump),
                "set EAE_BENCH=1 with a run/cloud-dump.txt to compare the saved sky");
        CloudDump.Snapshot sky = CloudDump.read(dump);
        List<CloudShape> all = new ArrayList<>();
        for (CloudDump.Entry e : sky.entries()) {
            all.addAll(e.formation().members());
        }
        CloudShadows.update(all, sky.time());
        CloudShadows shadows = CloudShadows.current();
        int compared = 0;
        for (CloudDump.Entry e : sky.entries()) {
            assertSame(e.formation(), e.camX(), e.camY(), e.camZ(), sky.time(), sky.voxel(), shadows,
                    e.formation().anchor().typeId() + " " + e.formation().regionId());
            compared++;
        }
        assertTrue(compared > 0);
    }
}
