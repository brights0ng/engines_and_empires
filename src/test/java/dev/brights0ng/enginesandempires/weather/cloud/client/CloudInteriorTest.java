package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.fog.FogTuning;

/**
 * The in-cloud probe: it must agree with the mesher voxel for voxel, or the 5-block fog would switch on a voxel
 * before or after the camera visibly passes the cloud's wall.
 */
class CloudInteriorTest {

    private static final UUID REGION = UUID.randomUUID();
    private static final int STRIDE = 5;

    private static CloudShape cloud(UUID id, double x, double z, float radius, float base, float top, float coverage,
                                    float tower, float anvil, int seed, String type) {
        return new CloudShape(id, REGION, "minecraft:overworld", x, (base + top) / 2, z, 0.3, 0, 0, 0,
                radius, base, top, 0.8f, coverage, 0.5f, 1, 0, 1, 1, type, tower, anvil, 1, 0.4f,
                "CLOUDY", 0.18f, 0, 0, seed);
    }

    private static CloudFormation cumulus() {
        return CloudFormation.of(REGION, List.of(cloud(new UUID(0, 1), 0, 0, 90, 120, 200, 1, 0.3f, 0, 7,
                "projectatmosphere:cumulus_mediocris")));
    }

    private static CloudFormation storm() {
        return CloudFormation.of(REGION, List.of(
                cloud(new UUID(0, 1), 0, 0, 260, 180, 520, 0.9f, 0.9f, 0.8f, 101, "projectatmosphere:cumulonimbus_calvus"),
                cloud(new UUID(0, 2), -220, 120, 180, 185, 380, 0.85f, 0.4f, 0.8f, 102,
                        "projectatmosphere:cumulus_congestus")));
    }

    @Test
    void probeMatchesTheMeshersVoxels() {
        for (CloudFormation f : List.of(cumulus(), storm())) {
            CloudField field = CloudField.of(f);
            double[] b = field.bounds();
            int size = 128;
            double time = 500;
            for (int s : new int[]{4, 8}) {
                long solid = 0, checked = 0, mismatched = 0;
                for (int sx = Math.floorDiv((int) Math.floor(b[0]), size); sx <= Math.floorDiv((int) Math.ceil(b[1]), size); sx++) {
                    for (int sy = Math.floorDiv((int) Math.floor(b[2]), size); sy <= Math.floorDiv((int) Math.ceil(b[3]), size); sy++) {
                        for (int sz = Math.floorDiv((int) Math.floor(b[4]), size); sz <= Math.floorDiv((int) Math.ceil(b[5]), size); sz++) {
                            CloudVoxelizer.SectionSetup st = CloudVoxelizer.sectionSetup(field, s, sx, sy, sz, size);
                            if (st == null) {
                                continue;
                            }
                            CloudVoxelizer.Cropped c = CloudVoxelizer.sample(field, st.grid(), s, time, st.own(),
                                    CloudVoxelizer.deepThreshold(field), st.k());
                            if (c == null) {
                                continue;
                            }
                            CloudVoxelizer.Grid g = c.grid();
                            int[] own = st.own();
                            // Every fifth voxel along each axis keeps the test quick (the probe redoes the lattice per
                            // voxel); offsets from the lattice still vary, so every interpolation position is hit.
                            for (int v = 0; v < g.ny(); v += STRIDE) {
                                for (int j = 0; j < g.nz(); j += STRIDE) {
                                    for (int i = 0; i < g.nx(); i += STRIDE) {
                                        int ai = g.gx0() + i, av = g.gy0() + v, aj = g.gz0() + j;
                                        if (ai < own[0] || ai >= own[1] || av < own[2] || av >= own[3]
                                                || aj < own[4] || aj >= own[5]) {
                                            continue;
                                        }
                                        boolean expected = c.solid()[(v * g.nz() + j) * g.nx() + i] != 0;
                                        boolean actual = CloudVoxelizer.voxelDensity(field, s, time, sx, sy, sz, size,
                                                (ai + 0.5) * s, (av + 0.5) * s, (aj + 0.5) * s) > 0;
                                        checked++;
                                        if (expected) {
                                            solid++;
                                        }
                                        if (expected != actual) {
                                            mismatched++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                assertTrue(solid > 50, "the test cloud has solid voxels at voxel " + s + ": " + solid);
                assertTrue(mismatched <= solid / 200, "probe and mesher disagree on " + mismatched + " of " + checked
                        + " voxels (" + solid + " solid) at voxel " + s);
            }
        }
    }

    @Test
    void probeIsFalseOutsideTheCloud() {
        CloudField field = CloudField.of(cumulus());
        // Far above the top and far to the side.
        assertTrue(CloudVoxelizer.voxelDensity(field, 8, 0, 0, 2, 0, 512, 0, 1100, 0) <= 0);
        assertTrue(CloudVoxelizer.voxelDensity(field, 8, 0, 20, 0, 0, 512, 10000, 150, 0) <= 0);
    }

    @Test
    void visibilityFollowsTypeAndLifecycle() {
        assertEquals(FogTuning.stormCloudVisibility, FogTuning.cloudVisibility("projectatmosphere:supercell"));
        assertEquals(FogTuning.stormCloudVisibility, FogTuning.cloudVisibility("projectatmosphere:nimbostratus"));
        assertEquals(FogTuning.congestusVisibility, FogTuning.cloudVisibility("projectatmosphere:stratus_nebulosus"));
        assertEquals(FogTuning.fairCloudVisibility, FogTuning.cloudVisibility("projectatmosphere:cumulus_humilis"));
        assertEquals(0, FogTuning.cloudVisibility("projectatmosphere:cirrus", 0));
        // Fully formed: the type's; barely there: the wisp's.
        assertEquals(FogTuning.stormCloudVisibility, FogTuning.cloudVisibility("cumulonimbus_calvus", 0), 1e-9);
        assertEquals(FogTuning.wispVisibility, FogTuning.cloudVisibility("cumulonimbus_calvus", 1), 1e-9);
        // A fully formed, healthy cloud isn't thin; a newborn one is.
        assertEquals(0, CloudInterior.thinness(cumulus()), 1e-9);
    }

    @Test
    void rainThickensTheFog() {
        assertEquals(FogTuning.heavyRainVisibility, FogTuning.rainVisibility(1, false), 1e-9);
        assertTrue(FogTuning.rainVisibility(0.2, false) > FogTuning.rainVisibility(0.8, false));
        assertEquals(FogTuning.rainVisibility(0.5, false) * 0.5, FogTuning.rainVisibility(0.5, true), 1e-9);
        assertTrue(Double.isInfinite(FogTuning.rainVisibility(0, false)));
    }
}
