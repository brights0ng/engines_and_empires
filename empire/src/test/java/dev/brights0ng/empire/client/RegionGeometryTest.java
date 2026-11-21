// Written by Gemini.
package dev.brights0ng.empire.client;

import static dev.brights0ng.empire.client.VeilMesh.HALF_W;
import static dev.brights0ng.empire.client.VeilMesh.LIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.RegionType;

class RegionGeometryTest {

    private static final double TOL = 1.0e-3;

    private record Quad(int kind, int style, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean horizontal() {
            return maxY - minY < 1.0e-5;
        }

        boolean normalX() {
            return maxX - minX < 1.0e-5;
        }

        boolean normalZ() {
            return maxZ - minZ < 1.0e-5;
        }

        double area() {
            if (horizontal()) {
                return (maxX - minX) * (maxZ - minZ);
            }
            if (normalX()) {
                return (maxY - minY) * (maxZ - minZ);
            }
            return (maxX - minX) * (maxY - minY);
        }
    }

    private static List<Quad> quads(VeilMesh m) {
        List<Quad> out = new ArrayList<>();
        for (int q = 0; q < m.quadCount(); q++) {
            double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int v = 0; v < 4; v++) {
                double[] p = {m.x(q, v) + m.originX(), m.y(q, v), m.z(q, v) + m.originZ()};
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], p[a]);
                    hi[a] = Math.max(hi[a], p[a]);
                }
            }
            out.add(new Quad(m.kind(q), m.style(q), lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
        }
        return out;
    }

    private static void assertNoHorizontalOverlap(List<Quad> qs) {
        for (int a = 0; a < qs.size(); a++) {
            for (int b = a + 1; b < qs.size(); b++) {
                Quad p = qs.get(a);
                Quad q = qs.get(b);
                double ox = Math.min(p.maxX(), q.maxX()) - Math.max(p.minX(), q.minX());
                double oz = Math.min(p.maxZ(), q.maxZ()) - Math.max(p.minZ(), q.minZ());
                assertFalse(ox > 1.0e-5 && oz > 1.0e-5, "overlapping quads " + p + " and " + q);
            }
        }
    }

    @Test
    void singleBlockHasSixInsetFacesFramedByChalkWithNoOverlap() {
        int style = RegionGeometry.styleFor(RegionType.HOME, true);
        Set<Long> blocks = Set.of(BlockKeys.pack(10, 64, 20));
        VeilMesh m = RegionGeometry.build(blocks, style, 10, 20);
        List<Quad> all = quads(m);

        assertFalse(all.isEmpty());
        assertTrue(all.stream().allMatch(q -> q.style() == style));

        List<Quad> top = all.stream().filter(q -> q.horizontal() && Math.abs(q.minY() - (65.0 - LIFT)) < TOL).toList();
        List<Quad> bottom = all.stream().filter(q -> q.horizontal() && Math.abs(q.minY() - (64.0 + LIFT)) < TOL).toList();
        List<Quad> west = all.stream().filter(q -> q.normalX() && Math.abs(q.minX() - (10.0 + LIFT)) < TOL).toList();
        List<Quad> east = all.stream().filter(q -> q.normalX() && Math.abs(q.minX() - (11.0 - LIFT)) < TOL).toList();
        List<Quad> north = all.stream().filter(q -> q.normalZ() && Math.abs(q.minZ() - (20.0 + LIFT)) < TOL).toList();
        List<Quad> south = all.stream().filter(q -> q.normalZ() && Math.abs(q.minZ() - (21.0 - LIFT)) < TOL).toList();

        double expectedVeil = (1.0 - 2.0 * HALF_W) * (1.0 - 2.0 * HALF_W);
        double expectedChalk = 1.0 - expectedVeil;
        for (List<Quad> face : List.of(top, bottom, west, east, north, south)) {
            assertFalse(face.isEmpty());
            double veilArea = face.stream().filter(q -> q.kind() == VeilMesh.VEIL_UNIFORM).mapToDouble(Quad::area).sum();
            double chalkArea = face.stream().filter(q -> q.kind() == VeilMesh.CHALK).mapToDouble(Quad::area).sum();
            assertEquals(expectedVeil, veilArea, TOL);
            assertEquals(expectedChalk, chalkArea, TOL);
        }
        assertNoHorizontalOverlap(top);
        assertNoHorizontalOverlap(bottom);
    }

    @Test
    void cubeInteriorFacesHaveOnlyVeilAndOuterEdgesHaveChalk() {
        Set<Long> blocks = new HashSet<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 64; y < 67; y++) {
                for (int z = 0; z < 3; z++) {
                    blocks.add(BlockKeys.pack(x, y, z));
                }
            }
        }
        VeilMesh m = RegionGeometry.build(blocks, RegionGeometry.styleFor(RegionType.HOME, true), 0, 0);
        List<Quad> top = quads(m).stream()
                .filter(q -> q.horizontal() && Math.abs(q.minY() - (67.0 - LIFT)) < TOL)
                .toList();

        assertNoHorizontalOverlap(top);
        double expectedVeil = (3.0 - 2.0 * HALF_W) * (3.0 - 2.0 * HALF_W);
        double expectedChalk = 9.0 - expectedVeil;
        double veilArea = top.stream().filter(q -> q.kind() == VeilMesh.VEIL_UNIFORM).mapToDouble(Quad::area).sum();
        double chalkArea = top.stream().filter(q -> q.kind() == VeilMesh.CHALK).mapToDouble(Quad::area).sum();
        assertEquals(expectedVeil, veilArea, TOL);
        assertEquals(expectedChalk, chalkArea, TOL);

        // Center cell (1, 66, 1) must be a single un-split VEIL_UNIFORM quad with no chalk.
        List<Quad> center = top.stream()
                .filter(q -> q.minX() >= 1.0 - TOL && q.maxX() <= 2.0 + TOL && q.minZ() >= 1.0 - TOL && q.maxZ() <= 2.0 + TOL)
                .toList();
        assertEquals(1, center.size());
        assertEquals(VeilMesh.VEIL_UNIFORM, center.get(0).kind());
        assertEquals(1.0, center.get(0).area(), TOL);
    }

    @Test
    void lShapedVolumeFramesConcaveInnerCornerAndCornerCap() {
        // Horizontal L-shape: (0,64,0), (1,64,0), (0,64,1) — missing (1,64,1)
        Set<Long> flatL = Set.of(
                BlockKeys.pack(0, 64, 0),
                BlockKeys.pack(1, 64, 0),
                BlockKeys.pack(0, 64, 1));
        VeilMesh flatMesh = RegionGeometry.build(flatL, RegionGeometry.styleFor(RegionType.HOME, true), 0, 0);
        List<Quad> top = quads(flatMesh).stream()
                .filter(q -> q.horizontal() && Math.abs(q.minY() - (65.0 - LIFT)) < TOL)
                .toList();
        assertNoHorizontalOverlap(top);
        // Corner cap at (1 - HALF_W .. 1, 1 - HALF_W .. 1) on block (0, 64, 0) must be CHALK
        boolean hasCap = top.stream().anyMatch(q -> q.kind() == VeilMesh.CHALK
                && Math.abs(q.minX() - (1.0 - HALF_W)) < TOL && Math.abs(q.maxX() - 1.0) < TOL
                && Math.abs(q.minZ() - (1.0 - HALF_W)) < TOL && Math.abs(q.maxZ() - 1.0) < TOL);
        assertTrue(hasCap, "inner corner of L-shape should get a chalk corner cap");

        // Vertical step: (0,64,0), (1,64,0), (0,65,0) — concave 270° crease along x = 1 on (1,64,0)'s top face
        Set<Long> step = Set.of(
                BlockKeys.pack(0, 64, 0),
                BlockKeys.pack(1, 64, 0),
                BlockKeys.pack(0, 65, 0));
        VeilMesh stepMesh = RegionGeometry.build(step, RegionGeometry.styleFor(RegionType.HOME, true), 0, 0);
        List<Quad> stepTop = quads(stepMesh).stream()
                .filter(q -> q.horizontal() && Math.abs(q.minY() - (65.0 - LIFT)) < TOL)
                .toList();
        boolean hasInnerCrease = stepTop.stream().anyMatch(q -> q.kind() == VeilMesh.CHALK
                && Math.abs(q.minX() - 1.0) < TOL && Math.abs(q.maxX() - (1.0 + HALF_W)) < TOL);
        assertTrue(hasInnerCrease, "concave step crease should get a chalk ribbon");
    }

    @Test
    void styleMappingCoversInvalidAndEveryRegionType() {
        assertEquals(RegionGeometry.STYLE_INVALID, RegionGeometry.styleFor(RegionType.HOME, false));
        assertEquals(RegionGeometry.STYLE_INVALID, RegionGeometry.styleFor(null, true));
        assertNull(RegionGeometry.typeForStyle(RegionGeometry.STYLE_INVALID));

        for (RegionType type : RegionType.values()) {
            int style = RegionGeometry.styleFor(type, true);
            assertNotEquals(RegionGeometry.STYLE_INVALID, style);
            assertEquals(type, RegionGeometry.typeForStyle(style));
        }
    }
}
