// Written by Claude (M8e4).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.greedyfluids.region.AirRegion;
import dev.brights0ng.greedyfluids.solve.Background3D;
import org.junit.jupiter.api.Test;

/** M8e4: {@code /meteodyne airflow hottest}'s spots. */
class AirflowHotSpotsTest {
    private static final int NX = 32, NY = 8, NZ = 16;

    /** Two 5 kW sources 23 blocks apart over a stone floor, stepped 30 s. */
    private static AirFrame twoFires() {
        CellGrid3D grid = new CellGrid3D(NX, NY, NZ);
        grid.fillSolid(0, 0, 0, NX - 1, 0, NZ - 1, Material.STONE);
        grid.setSource(4, 1, 8, 5000);
        grid.setSource(27, 1, 8, 5000);
        AirRegion r = new AirRegion(grid, Background3D.uniform(NX, NY, NZ, 10), 0, 0);
        for (int i = 0; i < 60; i++) {
            r.step();
        }
        return r.publish();
    }

    private static int apart(int[] a, int[] b) {
        return Math.max(Math.abs(a[0] - b[0]), Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
    }

    @Test
    void twoSpotsFindBothFires() {
        AirFrame f = twoFires();
        AirflowHotSpots s = AirflowHotSpots.of(f, 2, AirflowHotSpots.SEPARATION);
        List<int[]> c = s.cells();
        assertEquals(2, c.size());
        int[] west = c.get(0)[0] < c.get(1)[0] ? c.get(0) : c.get(1), east = west == c.get(0) ? c.get(1) : c.get(0);
        assertTrue(Math.abs(west[0] - 4) <= 4 && Math.abs(east[0] - 27) <= 4,
                "one spot over each fire: x " + west[0] + " and " + east[0]);
        assertEquals(NX * (NY - 1) * NZ, s.airCells());
        assertTrue(s.mean() > 10, "the air warmed: " + s.mean());
    }

    @Test
    void spotsAreHottestFirstAndApart() {
        AirFrame f = twoFires();
        List<int[]> c = AirflowHotSpots.of(f, 10, AirflowHotSpots.SEPARATION).cells();
        assertTrue(c.size() > 2);
        for (int i = 0; i < c.size(); i++) {
            for (int j = i + 1; j < c.size(); j++) {
                assertTrue(apart(c.get(i), c.get(j)) >= AirflowHotSpots.SEPARATION, "spots " + i + " and " + j);
            }
            if (i > 0) {
                int[] a = c.get(i - 1), b = c.get(i);
                assertTrue(f.temperature(a[0] + 0.5, a[1] + 0.5, a[2] + 0.5)
                        >= f.temperature(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5), "hottest first at " + i);
            }
        }
    }
}
