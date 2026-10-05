package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;

/**
 * The supercell template, built from an 11-cluster formation like PA's {@code /pa cloud spawn supercell} (moving east,
 * +x, at 0.1 blocks per tick), at real proportions ({@link CloudScale}). The structure tests use the design's average
 * storm (no variety); {@link SupercellVarietyTest} covers the variety.
 */
class SupercellTest {

    static {
        // Exact meshes, so closedness can be checked exactly.
        CloudVoxelizer.seamOverlap = 0;
    }

    private static final UUID REGION = new UUID(0x5EC0L, 0xCE11L);

    @BeforeEach
    void averageStorms() {
        CloudTuning.variety = 0;
    }

    @AfterEach
    void restore() {
        CloudTuning.variety = 1;
    }

    private static CloudShape lobe(UUID region, int i, double x, double z, float r, float growth, float decay) {
        CloudScale.Heights hts = CloudScale.heights("supercell", region, growth);
        return new CloudShape(new UUID(0, i), region, "minecraft:overworld", x, 350, z, 0.1, 0, 0, 0,
                r, (float) hts.base(), (float) hts.top(), 0.8f, 0.9f, 0.5f, growth, decay, 1, 1, "supercell", 0.9f,
                0.74f, 1, 0.5f, "THUNDER_CORE", 0.62f, 0.8f, 0.7f, 1234);
    }

    /** Eleven lobes scattered around the origin, as PA lays them out. */
    static CloudFormation storm(float growth, float decay) {
        return storm(REGION, growth, decay);
    }

    /** The same, for another region (so another storm's variety). */
    static CloudFormation storm(UUID region, float growth, float decay) {
        List<CloudShape> lobes = new ArrayList<>();
        double[][] at = {{0, 0}, {320, 60}, {-280, 140}, {140, -330}, {-150, -290}, {420, -180}, {-420, -60},
                {260, 330}, {-200, 380}, {560, 120}, {-560, 220}};
        for (int i = 0; i < at.length; i++) {
            lobes.add(lobe(region, i + 1, at[i][0], at[i][1], 170 + 10 * (i % 3), growth, decay));
        }
        return CloudFormation.of(region, lobes);
    }

    /** Collects vertices four to a quad. */
    private static final class Mesh implements CloudVoxelizer.VertexSink {
        final List<float[]> v = new ArrayList<>();

        @Override
        public void vertex(float x, float y, float z, int argb) {
            v.add(new float[]{x, y, z});
        }

        /** Sum of the quads' area-weighted normals: zero for a closed surface. */
        double[] normalSum() {
            double sx = 0, sy = 0, sz = 0;
            for (int q = 0; q + 3 < v.size(); q += 4) {
                float[] a = v.get(q), b = v.get(q + 1), c = v.get(q + 2);
                double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
                double wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
                sx += uy * wz - uz * wy;
                sy += uz * wx - ux * wz;
                sz += ux * wy - uy * wx;
            }
            return new double[]{sx, sy, sz};
        }

        /** Total area of the quads. */
        double area() {
            double total = 0;
            for (int q = 0; q + 3 < v.size(); q += 4) {
                float[] a = v.get(q), b = v.get(q + 1), c = v.get(q + 2);
                double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
                double wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
                double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                total += Math.sqrt(nx * nx + ny * ny + nz * nz);
            }
            return total;
        }
    }

    private static Mesh build(CloudFormation f, int s, double time, CloudVoxelizer.Result[] out) {
        Mesh m = new Mesh();
        out[0] = CloudVoxelizer.build(f, s, time, m);
        return m;
    }

    private static double density(CloudField field, double x, double y, double z, double time) {
        CloudField.Column col = field.newColumn();
        field.column(x, z, time, col);
        return field.density(col, x, y, z, time, false);
    }

    @Test
    void itIsBuiltAsOneStormAtRealProportions() {
        CloudField field = CloudField.of(storm(1, 0));
        assertTrue(field.storm != null, "a supercell formation uses the storm template");
        assertEquals(0, field.members.size(), "not one dome per cluster");
        Supercell s = field.storm;
        // x0.2: base 100-400 above the ground, top 3,000-4,200.
        assertTrue(s.yb - CloudScale.GROUND_Y >= 100 && s.yb - CloudScale.GROUND_Y <= 400, "base " + s.yb);
        assertTrue(s.ya - CloudScale.GROUND_Y >= 3000 && s.ya - CloudScale.GROUND_Y <= 4200, "top " + s.ya);
        // The design's tower (Bright's tuning): about 0.45 x the height in radius.
        assertTrue(s.ru > 0.35 * s.h && s.ru < 0.55 * s.h, "updraft radius " + s.ru + ", height " + s.h);
        assertTrue(s.anvilDown > 2500, "the anvil reaches kilometres downwind: " + s.anvilDown);
    }

    @Test
    void closedMesh() {
        CloudVoxelizer.Result[] r = new CloudVoxelizer.Result[1];
        Mesh m = build(storm(1, 0), 32, 500, r);
        assertTrue(r[0].quads() > 0);
        double[] n = m.normalSum();
        assertEquals(0, n[0], 1);
        assertEquals(0, n[1], 1);
        assertEquals(0, n[2], 1);
    }

    @Test
    void sectionsJoinIntoTheSameClosedStorm() {
        CloudFormation f = storm(1, 0);
        CloudField field = CloudField.of(f);
        double[] b = field.bounds();
        int size = CloudTuning.sectionSize;
        Mesh all = new Mesh();
        int quads = 0;
        int sections = 0;
        for (int sx = Math.floorDiv((int) b[0], size); sx <= Math.floorDiv((int) b[1], size); sx++) {
            for (int sy = Math.floorDiv((int) b[2], size); sy <= Math.floorDiv((int) b[3], size); sy++) {
                for (int sz = Math.floorDiv((int) b[4], size); sz <= Math.floorDiv((int) b[5], size); sz++) {
                    CloudVoxelizer.Result r = CloudVoxelizer.buildSection(CloudField.of(f), 32, 500, sx, sy, sz, size, all);
                    quads += r.quads();
                    if (!r.isEmpty()) {
                        sections++;
                    }
                }
            }
        }
        CloudVoxelizer.Result[] whole = new CloudVoxelizer.Result[1];
        build(f, 32, 500, whole);
        System.out.println("supercell at voxel 32: whole " + whole[0].quads() + " quads, " + sections + " sections "
                + quads + " quads");
        double[] n = all.normalSum();
        // Sections near the fine details sample on a finer lattice than their neighbours, so where both are deep
        // inside they can disagree about a hidden face between them (never about a visible one: holes need both to
        // be wrong by the 96-block deep margin). So: closed up to a sliver of the total area.
        double area = all.area();
        double open = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        System.out.printf("sections: unmatched hidden faces %.0f of %.0f square blocks (%.3f%%)%n", open, area,
                100 * open / area);
        assertTrue(open < 0.002 * area, "sections together are closed: " + open + " of " + area);
        assertTrue(sections > 4, "the storm spans several sections");
        // Seams near the surface add a few closing faces, but not many.
        assertTrue(quads < whole[0].quads() * 1.6, quads + " vs " + whole[0].quads());
    }

    @Test
    void oneUpdraftUnderTheTopAndTheAnvilDownwind() {
        CloudFormation f = storm(1, 0);
        Supercell s = CloudField.of(f).storm;
        CloudVoxelizer.Result[] r = new CloudVoxelizer.Result[1];
        Mesh m = build(f, 32, 500, r);
        // The highest vertices (the overshooting top) sit over the top of the updraft.
        float top = r[0].maxY();
        double sumX = 0, sumZ = 0;
        int n = 0;
        for (float[] p : m.v) {
            if (p[1] >= top - 32) {
                sumX += p[0];
                sumZ += p[2];
                n++;
            }
        }
        double topX = sumX / n, topZ = sumZ / n;
        double dist = Math.hypot(topX - s.ax, topZ - s.az);
        assertTrue(dist < s.ru * 1.5, "the highest point is over the updraft: " + dist + " blocks off, ru " + s.ru);
        // From the tower's base, the anvil streams east (+x, downwind) further than it reaches west.
        assertTrue(s.leanTop + s.anvilDown > s.anvilUp - s.leanTop,
                "anvil downwind: east " + (s.leanTop + s.anvilDown) + ", west " + (s.anvilUp - s.leanTop));
        assertTrue(r[0].maxX() > s.ax + 0.8 * s.anvilDown, "and the mesh reaches out there");
    }

    @Test
    void backshearOverhangsTheUpdraftUpwind() {
        CloudField field = CloudField.of(storm(1, 0));
        Supercell s = field.storm;
        assertTrue(s.anvilUp - 1.25 * s.ru > 900, "it overhangs the tower by kilometres: " + (s.anvilUp - 1.25 * s.ru));
        // Just under the anvil top, well upwind of the updraft's top (and to its left, clear of the flanking line).
        double u = -0.7 * s.anvilUp, w = -0.5 * s.anvilWidthUp;
        double x = s.ax + u * s.dx + w * s.rx;
        double z = s.az + u * s.dz + w * s.rz;
        double y = s.ya - 0.04 * s.h;
        assertTrue(density(field, x, y, z, 500) > 0, "the backshear is there");
        // ...and it is an overhang: well below it, at the same spot, there is no cloud.
        assertTrue(density(field, x, s.ya - 0.5 * s.h, z, 500) < 0, "with clear air under it");
    }

    @Test
    void forwardFlankAndShelfCloudDownwindLeft() {
        CloudField field = CloudField.of(storm(1, 0));
        Supercell s = field.storm;
        // Drift +x: downwind is +x, left is -z.
        assertTrue(s.ffX > s.ux, "forward flank downwind of the updraft");
        assertTrue(s.ffZ < s.uz, "and to its left");
        assertTrue(2 * s.ffAlong >= 0.3 * s.anvilDown, "a good part of the anvil's length: " + 2 * s.ffAlong);
        assertTrue(density(field, s.ffX, s.yb + 0.25 * s.h, s.ffZ, 500) > 0, "the forward flank's mass");
        assertTrue(density(field, s.ffX + 0.7 * s.ffAlong, s.yb + 0.25 * s.h, s.ffZ, 500) > 0, "all along it");
        // The shelf: low, just behind its leading edge, ahead of the forward flank.
        double shelfX = s.ffX + s.shelfReach - 0.35 * s.shelfDepth;
        assertTrue(density(field, shelfX, s.yb + 0.01 * s.h, s.ffZ, 500) > 0, "the shelf's low wedge");
        assertTrue(density(field, shelfX, s.yb + 0.3 * s.h, s.ffZ, 500) < 0, "with clear air above its nose");
    }

    @Test
    void nothingHangsBelowTheBaseButTheWallCloud() {
        CloudField field = CloudField.of(storm(1, 0));
        Supercell s = field.storm;
        double[] b = field.bounds();
        double y = s.yb - 0.03 * s.h;
        int below = 0;
        for (double x = b[0]; x < b[1]; x += 64) {
            for (double z = b[4]; z < b[5]; z += 64) {
                if (Math.hypot(x - s.wallX, z - s.wallZ) < s.wallR * 1.6) {
                    continue;
                }
                if (density(field, x, y, z, 500) > 0) {
                    below++;
                }
            }
        }
        assertEquals(0, below, "columns with cloud " + (int) (0.03 * s.h) + " blocks under the base");
    }

    @Test
    void theTowerLeansMoreWithHeightAndHasAWaist() {
        Supercell s = CloudField.of(storm(1, 0)).storm;
        assertTrue(s.leanAt(1) >= 0.2 * s.h, "the top is well downwind of the base");
        assertTrue(s.leanAt(0.5) < 0.5 * s.leanAt(1), "curving: little lean low down");
        assertTrue(s.widthAt(0) > s.widthAt(0.55) * 1.3, "wider at the base than the middle");
        assertTrue(s.widthAt(1) > s.widthAt(0.55) * 1.3, "flaring out into the anvil");
    }

    @Test
    void flankingLineTrailsToTheRightRear() {
        // Drift +x: right of the drift is +z, behind is -x. The flanking line's towers are behind and to the right.
        Supercell s = CloudField.of(storm(1, 0)).storm;
        int last = Supercell.TOWER_COUNT - 1;
        assertTrue(s.towerX[last] < s.ux, "behind the updraft");
        assertTrue(s.towerZ[last] > s.uz, "to its right");
        assertTrue(s.towerH[0] > s.towerH[last], "stepping down away from the updraft");
    }

    @Test
    void dyingStormLosesItsUpdraftBeforeItsAnvil() {
        Supercell mature = CloudField.of(storm(1, 0)).storm;
        Supercell dying = CloudField.of(storm(1, 0.55f)).storm;
        assertTrue(dying.updraftLife < mature.updraftLife * 0.5, "the updraft collapses first");
        assertEquals(mature.anvilLife, dying.anvilLife, 1e-9, "the anvil lingers");
        Supercell young = CloudField.of(storm(0.35f, 0)).storm;
        assertTrue(young.updraftLife > young.anvilLife, "a young storm has its updraft before its anvil");
        assertTrue(young.shelfLife == 0, "no shelf on a young storm");
    }

    /**
     * What the mesher does for a player under the forward flank: every section within the draw distance, each at its
     * band's voxel size (configured size 4).
     */
    @Test
    void buildTimeFromUnderTheStorm() {
        CloudFormation f = storm(1, 0);
        CloudField probe = CloudField.of(f);
        Supercell s = probe.storm;
        double camX = s.ffX, camY = CloudScale.GROUND_Y + 2, camZ = s.ffZ;
        int size = CloudTuning.sectionSize;
        List<CloudMeshes.Planned> plan = CloudMeshes.plan(probe.bounds(), camX, camY, camZ, size, 4, 3072);
        CloudVoxelizer.buildSection(CloudField.of(f), 8, 0, 0, 0, 0, size, (x, y, z, c) -> { }); // warm up
        long total = 0, slowest = 0;
        int quads = 0, nonEmpty = 0;
        java.util.Map<Integer, long[]> byVoxel = new java.util.TreeMap<>();
        for (CloudMeshes.Planned p : plan) {
            long t0 = System.nanoTime();
            CloudVoxelizer.Result r = CloudVoxelizer.buildSection(CloudField.of(f), p.voxel(), 1234, p.sx(), p.sy(),
                    p.sz(), size, (x, y, z, c) -> { });
            long dt = System.nanoTime() - t0;
            total += dt;
            slowest = Math.max(slowest, dt);
            quads += r.quads();
            long[] v = byVoxel.computeIfAbsent(p.voxel(), k -> new long[3]);
            v[0]++;
            v[1] += r.isEmpty() ? 0 : 1;
            v[2] += dt;
            if (!r.isEmpty()) {
                nonEmpty++;
            }
        }
        System.out.printf("supercell from under the forward flank, voxel 4: %d sections (%d with cloud), %d quads, one"
                        + " whole rebuild %d ms of CPU (%.1f s at the default half-core budget), slowest section %d ms%n",
                plan.size(), nonEmpty, quads, total / 1_000_000, total / 1e9 / 0.5, slowest / 1_000_000);
        for (java.util.Map.Entry<Integer, long[]> e : byVoxel.entrySet()) {
            System.out.printf("  voxel %d: %d sections, %d with cloud, %d ms%n", e.getKey(), e.getValue()[0],
                    e.getValue()[1], e.getValue()[2] / 1_000_000);
        }
        assertTrue(slowest / 1_000_000 < 5000);
    }

    @Test
    void neighbouringSectionsDifferByAtMostTwoTimes() {
        CloudField field = CloudField.of(storm(1, 0));
        Supercell s = field.storm;
        int size = CloudTuning.sectionSize;
        List<CloudMeshes.Planned> plan = CloudMeshes.plan(field.bounds(), s.ffX, CloudScale.GROUND_Y + 2, s.ffZ, size,
                4, 3072);
        java.util.Map<Long, CloudMeshes.Planned> byKey = new java.util.HashMap<>();
        for (CloudMeshes.Planned p : plan) {
            byKey.put(CloudMeshes.key(p.sx(), p.sy(), p.sz()), p);
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int coarsest = 0;
        for (CloudMeshes.Planned p : plan) {
            coarsest = Math.max(coarsest, p.voxel());
            for (int[] d : dirs) {
                CloudMeshes.Planned n = byKey.get(CloudMeshes.key(p.sx() + d[0], p.sy() + d[1], p.sz() + d[2]));
                if (n != null) {
                    assertTrue(p.voxel() <= 2 * n.voxel(), p + " next to " + n);
                }
            }
        }
        assertTrue(coarsest >= 32, "far sections are still coarse");
        for (int i = 1; i < plan.size(); i++) {
            assertTrue(plan.get(i).distance() >= plan.get(i - 1).distance(), "nearest first");
        }
    }

    /** Anvil thickness (top minus bottom, mammatus left out by sampling where there are none) at anvil (u, w). */
    private static double anvilThickness(Supercell s, double u, double w) {
        double[] c = new double[s.cacheSize()];
        double x = s.ax + u * s.dx + w * s.rx;
        double z = s.az + u * s.dz + w * s.rz;
        s.column(c, x, z, x, z, 500);
        return c[Supercell.ANVIL_TOP] - c[Supercell.ANVIL_BOTTOM];
    }

    @Test
    void theAnvilHasNoStepWhereTheBackshearBegins() {
        Supercell s = CloudField.of(storm(1, 0)).storm;
        double ahead = anvilThickness(s, 1, 0);
        double behind = anvilThickness(s, -1, 0);
        assertEquals(ahead, behind, ahead * 0.02, "front " + ahead + ", back " + behind);
    }

    @Test
    void theAnvilThinsToARazorEdge() {
        Supercell s = CloudField.of(storm(1, 0)).storm;
        double u = 0.04 * s.anvilDown; // before the mammatus start
        double width = s.anvilWidth + s.anvilSpread * u;
        double middle = anvilThickness(s, u, 0);
        double edge = anvilThickness(s, u, 0.97 * width);
        assertTrue(edge < 0.15 * middle, "edge " + edge + " vs middle " + middle);
    }
}
