package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The voxel mesher on its own: closed meshes, faces facing out, bounds, size limits, determinism. */
class CloudVoxelizerTest {

    static {
        // Exact meshes, so closedness and bounds can be checked exactly.
        CloudVoxelizer.seamOverlap = 0;
    }

    private static CloudShape cloud(float radius, float base, float top, float coverage, float tower, float anvil,
                                    int seed) {
        return cloudAt(UUID.randomUUID(), 0, 0, radius, base, top, coverage, tower, anvil, seed);
    }

    private static final UUID REGION = UUID.randomUUID();

    private static CloudShape cloudAt(UUID id, double x, double z, float radius, float base, float top, float coverage,
                                      float tower, float anvil, int seed) {
        return cloudMoving(id, x, z, 0, 0, radius, base, top, coverage, tower, anvil, seed);
    }

    private static CloudShape cloudMoving(UUID id, double x, double z, double vx, double vz, float radius, float base,
                                          float top, float coverage, float tower, float anvil, int seed) {
        return new CloudShape(id, REGION, "minecraft:overworld", x, z, vx, vz, 0,
                radius, base, top, 0.8f, coverage, 0.5f, 1, 0, 0, "cumulus_mediocris", tower, anvil, 0.4f, 0.18f, 0, 0,
                seed);
    }

    private static void assertClosed(Mesh m, CloudVoxelizer.Result r) {
        double sx = 0, sy = 0, sz = 0;
        for (int q = 0; q < r.quads(); q++) {
            double[] n = m.areaNormal(q);
            sx += n[0];
            sy += n[1];
            sz += n[2];
        }
        // A closed surface's area-weighted normals cancel out exactly.
        assertEquals(0, sx, 1e-3);
        assertEquals(0, sy, 1e-3);
        assertEquals(0, sz, 1e-3);
    }

    /** Collects vertices four to a quad. */
    private static final class Mesh implements CloudVoxelizer.VertexSink {
        final List<float[]> v = new ArrayList<>();

        @Override
        public void vertex(float x, float y, float z, int argb) {
            v.add(new float[]{x, y, z});
        }

        /** Area-weighted normal of quad q (for a rectangle, area times its outward normal). */
        double[] areaNormal(int q) {
            float[] a = v.get(q * 4);
            float[] b = v.get(q * 4 + 1);
            float[] c = v.get(q * 4 + 2);
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            double wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
            return new double[]{uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx};
        }
    }

    @Test
    void meshIsClosed() {
        for (int seed = 1; seed <= 5; seed++) {
            for (CloudShape c : List.of(cloud(200, 200, 280, 0.8f, 0, 0, seed),
                    cloud(300, 180, 420, 0.9f, 0.8f, 0.7f, seed),
                    cloud(120, 220, 240, 0.4f, 0, 0, seed))) {
                Mesh m = new Mesh();
                CloudVoxelizer.Result r = CloudVoxelizer.build(c, 4, m);
                assertTrue(r.quads() > 0, "cloud has faces");
                assertEquals(r.quads() * 4, m.v.size());
                assertClosed(m, r);
            }
        }
    }

    @Test
    void formationMergesIntoOneClosedMesh() {
        // Three overlapping clusters, like a /pa cloud spawn group.
        List<CloudShape> members = List.of(
                cloudAt(new UUID(0, 1), 0, 0, 150, 200, 300, 0.85f, 0.3f, 0, 11),
                cloudAt(new UUID(0, 2), 130, 40, 110, 205, 280, 0.8f, 0, 0, 12),
                cloudAt(new UUID(0, 3), -90, -120, 120, 198, 290, 0.8f, 0, 0, 13));
        CloudFormation f = CloudFormation.of(REGION, members);
        Mesh merged = new Mesh();
        CloudVoxelizer.Result r = CloudVoxelizer.build(f, 4, 0, merged);
        assertClosed(merged, r);

        int separate = 0;
        for (CloudShape c : members) {
            separate += CloudVoxelizer.build(CloudFormation.of(REGION, List.of(c)), 4, 0, new Mesh()).quads();
        }
        assertTrue(r.quads() < separate, "overlaps merge away: " + r.quads() + " vs " + separate + " separate");
    }

    @Test
    void anchorIsTheLowestId() {
        CloudShape a = cloudAt(new UUID(0, 9), 50, 0, 100, 200, 260, 0.8f, 0, 0, 1);
        CloudShape b = cloudAt(new UUID(0, 3), 0, 0, 100, 200, 260, 0.8f, 0, 0, 2);
        assertEquals(b.id(), CloudFormation.of(REGION, List.of(a, b)).anchor().id());
    }

    @Test
    void shapeChurnsWithTime() {
        CloudFormation f = CloudFormation.of(REGION, List.of(cloud(200, 200, 300, 0.8f, 0.4f, 0, 5)));
        List<String> early = vertices(f, 0);
        List<String> soon = vertices(f, 20);
        List<String> later = vertices(f, CloudVoxelizer.PUFF_PERIOD_TICKS * 2);
        int changedSoon = difference(early, soon);
        int changedLater = difference(early, later);
        assertTrue(changedLater > 0, "the shape changes over minutes");
        assertTrue(changedSoon < changedLater, "a second changes it far less than minutes do: "
                + changedSoon + " vs " + changedLater);
    }

    private static List<String> vertices(CloudFormation f, double time) {
        List<String> out = new ArrayList<>();
        CloudVoxelizer.build(f, 4, time, (x, y, z, argb) -> out.add(x + "," + y + "," + z));
        return out;
    }

    private static int difference(List<String> a, List<String> b) {
        java.util.Set<String> sa = new java.util.HashSet<>(a);
        java.util.Set<String> sb = new java.util.HashSet<>(b);
        int d = 0;
        for (String s : sa) {
            if (!sb.contains(s)) {
                d++;
            }
        }
        for (String s : sb) {
            if (!sa.contains(s)) {
                d++;
            }
        }
        return d;
    }

    @Test
    void facesPointOutward() {
        // Over the whole mesh, faces should point away from the middle: sum of (normal . position) is the volume x 3.
        Mesh m = new Mesh();
        CloudVoxelizer.Result r = CloudVoxelizer.build(cloud(200, 200, 280, 0.9f, 0, 0, 7), 4, m);
        double volume3 = 0;
        for (int q = 0; q < r.quads(); q++) {
            double[] n = m.areaNormal(q);
            float[] a = m.v.get(q * 4);
            volume3 += n[0] * a[0] + n[1] * (a[1] - 240) + n[2] * a[2];
        }
        assertTrue(volume3 > 0, "outward winding gives a positive volume");
    }

    @Test
    void staysInsideItsBounds() {
        CloudShape c = cloud(250, 200, 300, 1, 0, 0, 3);
        CloudVoxelizer.Result r = CloudVoxelizer.build(c, 8, new Mesh());
        assertTrue(r.minY() >= 200 - 0.06 * 250 - 16 && r.maxY() <= 200 + 100 * 1.15 + 0.08 * 250 + 16,
                "between base and top: " + r.minY() + " to " + r.maxY());
        assertTrue(r.maxX() <= 250 * 2.2 && r.minX() >= -250 * 2.2, "within reach of the radius");
    }

    @Test
    void noCoverageNoMesh() {
        assertTrue(CloudVoxelizer.build(cloud(200, 200, 280, 0, 0, 0, 1), 4, new Mesh()).isEmpty());
    }

    @Test
    void sameSeedSameMesh() {
        CloudShape c = cloud(200, 200, 280, 0.7f, 0.3f, 0, 42);
        assertEquals(CloudVoxelizer.build(c, 4, new Mesh()), CloudVoxelizer.build(c, 4, new Mesh()));
    }

    @Test
    void hugeCloudsGetBiggerVoxels() {
        CloudShape huge = cloud(3000, 200, 600, 1, 0, 0, 1);
        int s = CloudVoxelizer.voxelSizeFor(huge, 4);
        assertTrue(s > 4 && s <= 64, "doubles until it fits: " + s);
        assertEquals(4, CloudVoxelizer.voxelSizeFor(cloud(300, 200, 280, 1, 0, 0, 1), 4));
    }

    /** A supercell-like formation: a towering main cell with an anvil, and satellites, drifting east (+x). */
    private static CloudFormation supercell() {
        return CloudFormation.of(REGION, List.of(
                cloudMoving(new UUID(0, 1), 0, 0, 0.3, 0, 260, 180, 520, 0.9f, 0.9f, 0.8f, 101),
                cloudMoving(new UUID(0, 2), -220, 120, 0.3, 0, 180, 185, 380, 0.85f, 0.4f, 0.8f, 102),
                cloudMoving(new UUID(0, 3), -180, -160, 0.3, 0, 170, 182, 360, 0.85f, 0.3f, 0.8f, 103),
                cloudMoving(new UUID(0, 4), 160, -60, 0.3, 0, 150, 190, 330, 0.8f, 0.2f, 0.8f, 104)));
    }

    @Test
    void supercellIsClosedAndItsAnvilReachesDownwind() {
        Mesh m = new Mesh();
        CloudVoxelizer.Result r = CloudVoxelizer.build(supercell(), 8, 0, m);
        assertClosed(m, r);
        // The anvil stretches with the drift (+x): the mesh reaches much further east than west.
        assertTrue(r.maxX() > -r.minX() * 1.2, "anvil downwind: east " + r.maxX() + ", west " + r.minX());
    }

    @Test
    void supercellBuildTime() {
        CloudFormation f = supercell();
        for (int warm = 0; warm < 2; warm++) {
            CloudVoxelizer.build(f, 4, warm * 100, new Mesh());
        }
        for (int s : new int[]{4, 8, 16}) {
            long t0 = System.nanoTime();
            int[] quads = new int[1];
            CloudVoxelizer.Result r = CloudVoxelizer.build(f, s, 1234, (x, y, z, argb) -> quads[0]++);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("supercell at voxel " + s + ": " + ms + " ms, " + r.quads() + " quads");
            assertTrue(ms < 3000, "a supercell builds in under 3 s even on a slow test machine: " + ms + " ms");
        }
    }

    @Test
    void seamOverlapStaysInsideTheSilhouette() {
        CloudShape c = cloud(60, 100, 160, 1, 0, 0, 9);
        Mesh exactMesh = new Mesh();
        CloudVoxelizer.Result exact = CloudVoxelizer.build(c, 4, exactMesh);
        CloudVoxelizer.seamOverlap = 0.03f;
        try {
            Mesh sealedMesh = new Mesh();
            CloudVoxelizer.Result sealed = CloudVoxelizer.build(c, 4, sealedMesh);
            assertEquals(exact.quads(), sealed.quads(), "the same faces");
            // Faces only slide under coplanar neighbours, never past a silhouette edge, so the outline is unchanged.
            assertEquals(exact.minX(), sealed.minX(), 1e-4, "nothing pokes out past the silhouette");
            assertEquals(exact.maxX(), sealed.maxX(), 1e-4);
            assertEquals(exact.minY(), sealed.minY(), 1e-4);
            assertEquals(exact.maxY(), sealed.maxY(), 1e-4);
            assertEquals(exact.minZ(), sealed.minZ(), 1e-4);
            assertEquals(exact.maxZ(), sealed.maxZ(), 1e-4);
            // ...but faces inside flat surfaces still overlap, to seal T-junctions there.
            int moved = 0;
            for (int k = 0; k < exactMesh.v.size(); k++) {
                float[] a = exactMesh.v.get(k), b = sealedMesh.v.get(k);
                if (a[0] != b[0] || a[1] != b[1] || a[2] != b[2]) {
                    moved++;
                }
            }
            assertTrue(moved > 0, "some edges are stretched under coplanar faces");
        } finally {
            CloudVoxelizer.seamOverlap = 0;
        }
    }

    @Test
    void cullingOnlyDropsSectionsThatWouldBeEmpty() {
        // Every section the plan-time cull drops must build empty: the cull is exact, never a visual change.
        CloudField field = CloudField.of(supercell());
        double[] b = field.bounds();
        int size = 128;
        int culled = 0, kept = 0;
        for (int s : new int[]{8, 16}) {
            for (int sx = Math.floorDiv((int) Math.floor(b[0]), size); sx <= Math.floorDiv((int) Math.ceil(b[1]), size); sx++) {
                for (int sy = Math.floorDiv((int) Math.floor(b[2]), size); sy <= Math.floorDiv((int) Math.ceil(b[3]), size); sy++) {
                    for (int sz = Math.floorDiv((int) Math.floor(b[4]), size); sz <= Math.floorDiv((int) Math.ceil(b[5]), size); sz++) {
                        if (CloudVoxelizer.sectionMayHaveCloud(field, s, 0, sx, sy, sz, size)) {
                            kept++;
                            continue;
                        }
                        culled++;
                        CloudVoxelizer.Result r = CloudVoxelizer.buildSection(field, s, 0, sx, sy, sz, size, new Mesh());
                        assertTrue(r.isEmpty(), "culled section " + sx + "," + sy + "," + sz + " at voxel " + s
                                + " has " + r.quads() + " quads");
                    }
                }
            }
        }
        assertTrue(culled > 0 && kept > 0, "some sky culled, some cloud kept: " + culled + " culled, " + kept + " kept");
    }
}
