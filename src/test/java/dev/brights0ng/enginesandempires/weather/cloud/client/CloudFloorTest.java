package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;

/**
 * No part of any cloud is drawn below {@link CloudScale#MIN_BASE_Y} (Bright, 2026-10-10): clouds sitting right on the
 * floor used to reach tens of blocks under it, through the vertical warp, a layer sheet's relief, the churn, the voxel
 * lattice and the wisps. Big real-sized clouds on the floor, meshed section by section as the renderer does.
 */
class CloudFloorTest {

    private static final double FLOOR = CloudScale.MIN_BASE_Y;
    private static final UUID REGION = UUID.randomUUID();

    private static CloudShape cloud(String type, int n, double x, double z, float radius, float thickness,
                                    float tower, float anvil, int seed) {
        return new CloudShape(new UUID(7, n), REGION, "minecraft:overworld", x, z, 0.3, 0.1, 0,
                radius, (float) FLOOR, (float) FLOOR + thickness, 0.95f, 0.95f, 0.6f, 1, 0, 0, type, tower, anvil,
                0.6f, 0.5f, 0.8f, 0.5f, seed);
    }

    /** A cumulonimbus capillatus at full size (radius 6 km at x0.2), and a satellite tower. */
    private static CloudFormation storm() {
        return CloudFormation.of(REGION, List.of(
                cloud("cumulonimbus_capillatus", 1, 0, 0, 1200, 2700, 0.9f, 0.8f, 11),
                cloud("cumulus_congestus", 2, -700, 300, 600, 900, 0.7f, 0, 12)));
    }

    /** A thick nimbostratus deck (about 1,100 blocks deep, its base undulating) of three domes. */
    private static CloudFormation nimbostratus() {
        return CloudFormation.of(REGION, List.of(
                cloud("nimbostratus", 3, 0, 0, 1000, 1100, 0, 0, 21),
                cloud("nimbostratus", 4, 1300, 200, 1000, 1000, 0, 0, 22),
                cloud("nimbostratus", 5, 600, -1100, 1000, 1050, 0, 0, 23)));
    }

    /** A stratocumulus deck, whose puffs bulge below the deck's base. */
    private static CloudFormation stratocumulus() {
        return CloudFormation.of(REGION, List.of(
                cloud("stratocumulus", 6, 0, 0, 500, 160, 0, 0, 31),
                cloud("stratocumulus", 7, 650, 100, 500, 140, 0, 0, 32)));
    }

    /** The lowest vertex of every section from below the floor up to 256 blocks above it. */
    private static double lowestVertex(CloudFormation f, double time) {
        CloudField field = CloudField.of(f);
        double[] b = field.bounds();
        int size = 128;
        double[] lowest = {Double.MAX_VALUE};
        int sections = 0;
        for (int s : new int[]{8, 16}) {
            // From well below the floor (where the cloud used to reach) to a little above it.
            int sy0 = Math.floorDiv((int) FLOOR - 128, size), sy1 = Math.floorDiv((int) FLOOR + 256, size);
            for (int sx = Math.floorDiv((int) Math.floor(b[0]), size); sx <= Math.floorDiv((int) Math.ceil(b[1]), size); sx++) {
                for (int sz = Math.floorDiv((int) Math.floor(b[4]), size); sz <= Math.floorDiv((int) Math.ceil(b[5]), size); sz++) {
                    for (int sy = sy0; sy <= sy1; sy++) {
                        CloudVoxelizer.Result r = CloudVoxelizer.buildSection(field, s, time, sx, sy, sz, size,
                                (x, y, z, argb) -> lowest[0] = Math.min(lowest[0], y));
                        if (!r.isEmpty()) {
                            sections++;
                        }
                    }
                }
            }
        }
        assertTrue(sections > 0, "the cloud was meshed near the floor");
        return lowest[0];
    }

    private static void assertAboveFloor(String what, CloudFormation f) {
        for (double time : new double[]{0, 4321, 77777}) {
            double low = lowestVertex(f, time);
            // (Faces may slide a hair under coplanar neighbours to seal seams: CloudVoxelizer.seamOverlap.)
            assertTrue(low >= FLOOR - 0.1, what + " at time " + time + " reaches down to y " + low);
        }
    }

    @Test
    void stormsStayAboveTheFloor() {
        assertAboveFloor("cumulonimbus", storm());
    }

    @Test
    void nimbostratusStaysAboveTheFloor() {
        assertAboveFloor("nimbostratus", nimbostratus());
    }

    @Test
    void stratocumulusStaysAboveTheFloor() {
        assertAboveFloor("stratocumulus", stratocumulus());
    }

    @Test
    void wholeFormationBuildsStayAboveTheFloor() {
        for (CloudFormation f : List.of(storm(), nimbostratus(), stratocumulus())) {
            int s = CloudVoxelizer.voxelSizeFor(f, 8);
            CloudVoxelizer.Result r = CloudVoxelizer.build(f, s, 1234, (x, y, z, argb) -> { });
            assertTrue(r.isEmpty() || r.minY() >= FLOOR - 0.1, "whole build at voxel " + s + " reaches y " + r.minY());
        }
    }

    @Test
    void wispsStayAboveTheFloor() {
        CloudField field = CloudField.of(storm());
        CloudWisps wisps = new CloudWisps(99);
        for (int t = 0; t < 200; t++) {
            wisps.tick(field, 0, t, 1000);
        }
        for (CloudWisps.Wisp w : wisps.wisps) {
            assertTrue(CloudWisps.lowest(w) >= FLOOR, "a wisp reaches y " + CloudWisps.lowest(w));
        }
    }
}
