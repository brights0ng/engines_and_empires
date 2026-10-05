package dev.brights0ng.enginesandempires.weather.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SilhouetteTest {

    /** A solid box from (0,0,0), sx by sy by sz blocks. */
    private static Silhouette box(int sx, int sy, int sz) {
        Silhouette s = new Silhouette();
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    s.add(x, y, z);
                }
            }
        }
        return s;
    }

    @Test
    void boxAreasAreItsFaces() {
        Silhouette s = box(10, 3, 4);
        assertEquals(3 * 4, s.area(0));
        assertEquals(10 * 4, s.area(1));
        assertEquals(10 * 3, s.area(2));
        assertEquals(12, s.projectedArea(1, 0, 0), 1e-9);
        assertEquals(30, s.projectedArea(0, 0, -5), 1e-9, "only the direction matters");
    }

    @Test
    void diagonalWindSeesBothFaces() {
        Silhouette s = box(10, 3, 4);
        double d = Math.sqrt(0.5);
        assertEquals((12 + 30) * d, s.projectedArea(1, 0, 1), 1e-9);
    }

    @Test
    void hollowInsidesDontCount() {
        Silhouette solid = box(5, 5, 5);
        Silhouette hollow = box(5, 5, 5);
        hollow.remove(2, 2, 2);
        assertEquals(solid.area(0), hollow.area(0));
        assertEquals(solid.area(1), hollow.area(1));
    }

    @Test
    void removingTheLastBlockOfAColumnShrinksTheOutline() {
        Silhouette s = new Silhouette();
        s.add(0, 0, 0);
        s.add(1, 0, 0);
        assertEquals(1, s.area(0), "two blocks in one column seen along X");
        s.remove(1, 0, 0);
        assertEquals(1, s.area(0));
        s.remove(0, 0, 0);
        assertTrue(s.isEmpty());
        s.remove(0, 0, 0);
        assertTrue(s.isEmpty(), "removing an absent block does nothing");
    }

    @Test
    void aBoxIsPushedThroughItsMiddle() {
        Silhouette s = box(4, 2, 6);
        double[] out = new double[3];
        assertTrue(s.centreOfPressure(1, 0, 0, 2, 1, 3, out));
        assertEquals(2, out[0], 1e-9, "along the wind: the centre of mass");
        assertEquals(1, out[1], 1e-9);
        assertEquals(3, out[2], 1e-9);
    }

    @Test
    void aTallMastRaisesTheCentreOfPressure() {
        // A 10x1x3 hull with a 1x8x1 mast in the middle, wind along +Z.
        Silhouette s = box(10, 1, 3);
        for (int y = 1; y <= 8; y++) {
            s.add(5, y, 1);
        }
        double[] out = new double[3];
        s.centreOfPressure(0, 0, 1, 5, 1, 1.5, out);
        assertTrue(out[1] > 1.5, "the push acts above the hull, so the ship heels: " + out[1]);
    }

    @Test
    void noOutlineMeansNoPressure() {
        double[] out = new double[3];
        assertFalse(new Silhouette().centreOfPressure(1, 0, 0, 7, 8, 9, out));
        assertEquals(7, out[0]);
        assertEquals(0, new Silhouette().projectedArea(1, 0, 0));
    }
}
