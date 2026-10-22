// Written by Claude.
package dev.brights0ng.empire.client;

import static dev.brights0ng.empire.client.TalcBorderGeometry.HALF_W;
import static dev.brights0ng.empire.client.TalcBorderGeometry.LIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.client.TalcBorderGeometry.Borders;
import dev.brights0ng.empire.client.TalcBorderGeometry.Mesh;

class TalcBorderGeometryTest {

    private static final double TOL = 1.0e-3;

    /** Columns of full blocks up to a surface height, plus extra boxes at single blocks. */
    private static final class FakeTerrain implements TalcBorderGeometry.Terrain {
        final Map<Long, Integer> ground = new HashMap<>();
        final Map<List<Integer>, double[][]> extra = new HashMap<>();
        int defaultTop = 64;

        int top(int x, int z) {
            return ground.getOrDefault(TalcBorderGeometry.key(x, z), defaultTop);
        }

        FakeTerrain set(int x0, int x1, int z0, int z1, int top) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    ground.put(TalcBorderGeometry.key(x, z), top);
                }
            }
            return this;
        }

        @Override
        public int scanTop(int x, int z) {
            return 120;
        }

        @Override
        public int scanBottom() {
            return 0;
        }

        @Override
        public void boxes(int x, int y, int z, TalcBorderGeometry.BoxSink sink) {
            double[][] e = extra.get(List.of(x, y, z));
            if (e != null) {
                for (double[] b : e) {
                    sink.box(b[0], b[1], b[2], b[3], b[4], b[5]);
                }
                return;
            }
            if (y < top(x, z)) {
                sink.box(0, 0, 0, 1, 1, 1);
            }
        }
    }

    private record Quad(int kind, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean horizontal() {
            return maxY - minY < 1.0e-6;
        }

        boolean normalX() {
            return !horizontal() && maxX - minX < 1.0e-6;
        }

        boolean normalZ() {
            return !horizontal() && maxZ - minZ < 1.0e-6;
        }

        double height() {
            return maxY - minY;
        }
    }

    private static List<Quad> quads(Mesh m) {
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
            out.add(new Quad(m.kind(q), lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
        }
        return out;
    }

    private static List<Quad> lines(Mesh m, Predicate<Quad> filter) {
        return quads(m).stream().filter(q -> q.kind() == Mesh.LINE).filter(filter).toList();
    }

    private static Borders straightNorthBorder(int x0, int x1, int z) {
        Borders b = new Borders();
        for (int x = x0; x < x1; x++) {
            b.addZEdge(x, z, 1);
        }
        return b;
    }

    private static void assertNoHorizontalOverlap(List<Quad> tops) {
        for (int a = 0; a < tops.size(); a++) {
            for (int b = a + 1; b < tops.size(); b++) {
                Quad p = tops.get(a);
                Quad q = tops.get(b);
                double ox = Math.min(p.maxX(), q.maxX()) - Math.max(p.minX(), q.minX());
                double oz = Math.min(p.maxZ(), q.maxZ()) - Math.max(p.minZ(), q.minZ());
                assertFalse(ox > 1.0e-6 && oz > 1.0e-6, "overlapping top quads " + p + " and " + q);
            }
        }
    }

    private static double area(List<Quad> tops) {
        return tops.stream().mapToDouble(q -> (q.maxX() - q.minX()) * (q.maxZ() - q.minZ())).sum();
    }

    @Test
    void flatStraightBorderIsACentredRibbonWithSquareEnds() {
        Mesh m = TalcBorderGeometry.build(new FakeTerrain(), straightNorthBorder(0, 4, 0), 0, 0);
        List<Quad> all = lines(m, q -> true);
        assertTrue(all.stream().allMatch(Quad::horizontal), "no vertical line quads on flat ground");
        assertTrue(all.stream().allMatch(q -> Math.abs(q.minY() - (64 + LIFT)) < TOL));
        assertTrue(all.stream().allMatch(q -> q.minZ() >= -HALF_W - TOL && q.maxZ() <= HALF_W + TOL));
        assertNoHorizontalOverlap(all);
        assertEquals((4 + 2 * HALF_W) * 2 * HALF_W, area(all), TOL);
        double veilLength = quads(m).stream().filter(q -> q.kind() == Mesh.VEIL).mapToDouble(q -> q.maxX() - q.minX()).sum();
        assertEquals(4.0, veilLength, TOL);
    }

    @Test
    void cliffAlongBorderPaintsOnlyTheHigherSideWithLipAndBaseBands() {
        FakeTerrain t = new FakeTerrain().set(-3, 6, -3, -1, 70);
        Mesh m = TalcBorderGeometry.build(t, straightNorthBorder(0, 4, 0), 0, 0);

        List<Quad> tops = lines(m, Quad::horizontal);
        assertTrue(tops.stream().noneMatch(q -> q.minY() < 65), "lower side gets no ribbon");
        assertTrue(tops.stream().filter(q -> q.minX() >= -TOL && q.maxX() <= 4 + TOL)
                .allMatch(q -> q.minZ() >= -HALF_W - TOL && q.maxZ() <= TOL));

        List<Quad> cliff = lines(m, q -> q.normalZ() && Math.abs(q.minZ()) < 0.01 && q.minX() >= -TOL && q.maxX() <= 4 + TOL);
        assertFalse(cliff.isEmpty());
        assertTrue(cliff.stream().allMatch(q -> q.height() <= HALF_W + LIFT + TOL), "no full-face paint: " + cliff);
        assertTrue(cliff.stream().anyMatch(q -> Math.abs(q.maxY() - (70 + LIFT)) < TOL), "lip band");
        assertTrue(cliff.stream().anyMatch(q -> Math.abs(q.minY() - 64) < TOL), "base band");

        List<Quad> veils = quads(m).stream().filter(q -> q.kind() == Mesh.VEIL).toList();
        assertTrue(veils.stream().allMatch(q -> Math.abs(q.minY() - (70 + LIFT)) < TOL), "veil rises from the higher side");
    }

    @Test
    void cliffOnTheOtherSideMovesTheRibbonThere() {
        FakeTerrain t = new FakeTerrain().set(-3, 6, 0, 3, 70);
        Mesh m = TalcBorderGeometry.build(t, straightNorthBorder(0, 4, 0), 0, 0);
        List<Quad> tops = lines(m, q -> q.horizontal() && q.minX() >= -TOL && q.maxX() <= 4 + TOL);
        assertFalse(tops.isEmpty());
        assertTrue(tops.stream().allMatch(q -> q.minY() > 69 && q.minZ() >= -TOL && q.maxZ() <= HALF_W + TOL));
        List<Quad> cliff = lines(m, q -> q.normalZ() && Math.abs(q.minZ()) < 0.01 && q.minX() >= -TOL && q.maxX() <= 4 + TOL);
        assertFalse(cliff.isEmpty());
        assertTrue(cliff.stream().allMatch(q -> q.height() <= HALF_W + LIFT + TOL));
    }

    @Test
    void oneBlockStepAlongBorderFoldsOverTheEdgeWithNoBaseBand() {
        FakeTerrain t = new FakeTerrain().set(-3, 6, -3, -1, 65);
        Mesh m = TalcBorderGeometry.build(t, straightNorthBorder(0, 4, 0), 0, 0);
        List<Quad> face = lines(m, q -> q.normalZ() && Math.abs(q.minZ()) < 0.01 && q.minX() >= -TOL && q.maxX() <= 4 + TOL);
        assertFalse(face.isEmpty());
        assertTrue(face.stream().allMatch(q -> Math.abs(q.minY() - (65 - HALF_W)) < TOL && Math.abs(q.maxY() - (65 + LIFT)) < TOL),
                "only the band over the edge: " + face);
        assertTrue(lines(m, Quad::horizontal).stream().noneMatch(q -> q.minY() < 65), "lower side gets no ribbon");
    }

    @Test
    void stepAlongTheLineIsANarrowFullHeightStripe() {
        FakeTerrain t = new FakeTerrain().set(2, 6, -3, 3, 67);
        Mesh m = TalcBorderGeometry.build(t, straightNorthBorder(0, 4, 0), 0, 0);
        List<Quad> step = lines(m, q -> q.normalX() && Math.abs(q.minX() - 2) < 0.01);
        assertFalse(step.isEmpty());
        assertTrue(step.stream().allMatch(q -> Math.abs(q.minY() - 64) < TOL && Math.abs(q.maxY() - (67 + LIFT)) < TOL));
        double width = step.stream().mapToDouble(q -> q.maxZ() - q.minZ()).sum();
        assertEquals(2 * HALF_W, width, TOL);
    }

    @Test
    void lowStepIsPaintedWhole() {
        FakeTerrain t = new FakeTerrain();
        for (int x = -1; x <= 4; x++) {
            t.extra.put(List.of(x, 64, -1), new double[][]{{0, 0, 0, 1, 0.15, 1}});
        }
        Mesh m = TalcBorderGeometry.build(t, straightNorthBorder(0, 4, 0), 0, 0);
        List<Quad> faces = lines(m, q -> q.normalZ() && Math.abs(q.minZ()) < 0.01 && q.minX() >= -TOL && q.maxX() <= 4 + TOL);
        assertFalse(faces.isEmpty());
        assertTrue(faces.stream().allMatch(q -> Math.abs(q.minY() - 64) < TOL && Math.abs(q.maxY() - (64.15 + LIFT)) < TOL));
    }

    @Test
    void closedLoopHasNoOverlapsOrGapsAtCorners() {
        Borders b = new Borders();
        b.addZEdge(0, 0, 1);
        b.addZEdge(1, 0, 1);
        b.addZEdge(0, 2, 1);
        b.addZEdge(1, 2, 1);
        b.addXEdge(0, 0, 1);
        b.addXEdge(0, 1, 1);
        b.addXEdge(2, 0, 1);
        b.addXEdge(2, 1, 1);
        Mesh m = TalcBorderGeometry.build(new FakeTerrain(), b, 0, 0);
        List<Quad> tops = lines(m, Quad::horizontal);
        assertNoHorizontalOverlap(tops);
        double outer = 2 + 2 * HALF_W;
        double inner = 2 - 2 * HALF_W;
        assertEquals(outer * outer - inner * inner, area(tops), TOL);
    }

    @Test
    void postSetBackFromTheBorderLeavesTheLineOnTheGround() {
        FakeTerrain t = new FakeTerrain();
        t.extra.put(List.of(1, 64, -1), new double[][]{{0.375, 0, 0.375, 0.625, 1, 0.625}});
        Mesh m = TalcBorderGeometry.build(t, straightNorthBorder(0, 4, 0), 0, 0);
        assertTrue(lines(m, q -> true).stream().allMatch(q -> q.horizontal() && q.minY() < 64.5));
    }

    @Test
    void overhangGetsALipButNoBaseBand() {
        FakeTerrain t = new FakeTerrain();
        for (int x = -1; x <= 4; x++) {
            t.extra.put(List.of(x, 70, -1), new double[][]{{0, 0, 0, 1, 1, 1}});
        }
        Mesh m = TalcBorderGeometry.build(t, straightNorthBorder(0, 4, 0), 0, 0);
        List<Quad> face = lines(m, q -> q.normalZ() && Math.abs(q.minZ()) < 0.01 && q.minX() >= -TOL && q.maxX() <= 4 + TOL);
        assertFalse(face.isEmpty());
        assertTrue(face.stream().allMatch(q -> q.minY() >= 71 - HALF_W - TOL), "nothing painted below the overhang: " + face);
    }
}
