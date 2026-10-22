// Written by Gemini.
package dev.brights0ng.seismicsurveyance.client;

import dev.brights0ng.sparseores.shape.oil.CrescentPotential;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPolygonRendererTest {

    @Test
    void deduplicationRemovesConsecutiveAndWrappingDuplicates() {
        double[][] pts = new double[][]{
                {0, 0}, {0, 0}, {1, 0}, {1, 1}, {1, 1}, {0, 1}, {0, 0}
        };
        List<double[]> clean = MapPolygonRenderer.deduplicate(pts, 1e-6);
        assertEquals(4, clean.size());
        assertEquals(0.0, clean.get(0)[0]);
        assertEquals(0.0, clean.get(0)[1]);
        assertEquals(1.0, clean.get(1)[0]);
        assertEquals(0.0, clean.get(1)[1]);
        assertEquals(1.0, clean.get(2)[0]);
        assertEquals(1.0, clean.get(2)[1]);
        assertEquals(0.0, clean.get(3)[0]);
        assertEquals(1.0, clean.get(3)[1]);
    }

    @Test
    void convexPolygonTriangulationPreservesArea() {
        double[][] square = new double[][]{
                {0, 0}, {10, 0}, {10, 10}, {0, 10}
        };
        List<double[][]> tris = MapPolygonRenderer.triangulate(square);
        assertEquals(2, tris.size());

        double totalArea = 0.0;
        for (double[][] tri : tris) {
            double a = MapPolygonRenderer.signedArea(List.of(tri[0], tri[1], tri[2]));
            assertTrue(a > 0, "Each triangle must have positive area");
            totalArea += a;
        }
        assertEquals(100.0, totalArea, 1e-4);
    }

    @Test
    void concavePolygonInteriorDoesNotLeakIntoConcaveCavity() {
        // L-shaped polygon with a concave inner corner at (2, 2)
        // Vertices: (0,0) -> (4,0) -> (4,2) -> (2,2) -> (2,4) -> (0,4)
        // The region (2..4, 2..4) is external space (concave notch).
        // (3, 3) is strictly outside the polygon.
        double[][] lShape = new double[][]{
                {0, 0}, {4, 0}, {4, 2}, {2, 2}, {2, 4}, {0, 4}
        };
        List<double[][]> tris = MapPolygonRenderer.triangulate(lShape);
        assertEquals(4, tris.size());

        double totalArea = 0.0;
        for (double[][] tri : tris) {
            double a = MapPolygonRenderer.signedArea(List.of(tri[0], tri[1], tri[2]));
            assertTrue(a > 0, "Triangle must have positive area");
            totalArea += a;

            // (3, 3) is inside the concave cavity. None of the triangles should contain it!
            boolean inside = pointInTri(new double[]{3, 3}, tri[0], tri[1], tri[2]);
            assertFalse(inside, "Triangle leaked into concave cavity at (3, 3)!");
        }
        // Total area of L shape: (4*4) - (2*2) = 12
        assertEquals(12.0, totalArea, 1e-4);
    }

    @Test
    void crescentOilReservoirTriangulationDoesNotLeak() {
        CrescentPotential crescent = new CrescentPotential(0, 0, 80.0, 30.0, Math.PI * 0.6, 0.0);
        double[][] poly = crescent.polygon(1.0, 64);
        assertTrue(poly.length >= 10);

        List<double[][]> tris = MapPolygonRenderer.triangulate(poly);
        assertTrue(tris.size() >= 8);

        double totalArea = 0.0;
        for (double[][] tri : tris) {
            double a = Math.abs(MapPolygonRenderer.signedArea(List.of(tri[0], tri[1], tri[2])));
            assertTrue(a > 0);
            totalArea += a;

            // Centroid of each triangle must lie inside active oil (potential > 0)
            double cx = (tri[0][0] + tri[1][0] + tri[2][0]) / 3.0;
            double cz = (tri[0][1] + tri[1][1] + tri[2][1]) / 3.0;
            assertTrue(crescent.potential(cx, cz) > 0.0,
                    "Triangle centroid (" + cx + ", " + cz + ") leaked into concave exterior void!");
        }

        double polyArea = Math.abs(MapPolygonRenderer.signedArea(MapPolygonRenderer.deduplicate(poly, 1e-6)));
        assertEquals(polyArea, totalArea, polyArea * 0.01, "Sum of triangle areas should match polygon area");
    }

    @Test
    void strokeRibbonConnectsSeamlesslyWithoutGapsOrCreases() {
        double[][] poly = new double[][]{
                {0, 0}, {50, 0}, {60, 30}, {30, 40}, {0, 20}
        };
        double halfWidth = 2.0;
        double[][][] ribbon = MapPolygonRenderer.strokeRibbon(poly, halfWidth);
        assertEquals(poly.length, ribbon.length);

        for (int i = 0; i < ribbon.length; i++) {
            int next = (i + 1) % ribbon.length;
            double[][] quadCurrent = ribbon[i];
            double[][] quadNext = ribbon[next];

            // Outer corner at vertex i+1 must match exactly between current and next quad
            assertEquals(quadCurrent[1][0], quadNext[0][0], 1e-9, "Outer x must match at corner");
            assertEquals(quadCurrent[1][1], quadNext[0][1], 1e-9, "Outer y must match at corner");

            // Inner corner at vertex i+1 must match exactly between current and next quad
            assertEquals(quadCurrent[2][0], quadNext[3][0], 1e-9, "Inner x must match at corner");
            assertEquals(quadCurrent[2][1], quadNext[3][1], 1e-9, "Inner y must match at corner");
        }
    }

    private static boolean pointInTri(double[] p, double[] a, double[] b, double[] c) {
        double cp1 = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
        double cp2 = (c[0] - b[0]) * (p[1] - b[1]) - (c[1] - b[1]) * (p[0] - b[0]);
        double cp3 = (a[0] - c[0]) * (p[1] - c[1]) - (a[1] - c[1]) * (p[0] - c[0]);

        if (cp1 > 0 && cp2 > 0 && cp3 > 0) return true;
        if (cp1 < 0 && cp2 < 0 && cp3 < 0) return true;
        return false;
    }
}
