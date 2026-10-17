package dev.brights0ng.paradynelib.heat.air;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class HeatClustersTest {

    @Test
    void oneSourceGetsItsMarginAllRound() {
        List<HeatClusters.Cluster> c = HeatClusters.cluster(new int[]{10}, new int[]{64}, new int[]{-5}, 12, 48, -64, 319);
        assertEquals(1, c.size());
        assertArrayEquals(new int[]{-2, 52, -17, 22, 76, 7}, c.get(0).box());
    }

    @Test
    void sourcesWhoseMarginsTouchShareARegionAndOthersDont() {
        // 0 and 20 are within 2 x 12: together; 60 is not
        List<HeatClusters.Cluster> c = HeatClusters.cluster(new int[]{0, 20, 60}, new int[]{64, 64, 64},
                new int[]{0, 0, 0}, 12, 96, -64, 319);
        assertEquals(2, c.size());
        assertEquals(2, c.get(0).members().length);
        assertArrayEquals(new int[]{-12, 52, -12, 32, 76, 12}, c.get(0).box());
        assertEquals(1, c.get(1).members().length);
    }

    @Test
    void boxesStayInsideTheWorldsHeight() {
        List<HeatClusters.Cluster> c = HeatClusters.cluster(new int[]{0}, new int[]{-60}, new int[]{0}, 12, 48, -64, 319);
        assertEquals(-64, c.get(0).box()[1]);
    }

    @Test
    void bigGroupsAreCutIntoRegionsThatDontOverlap() {
        // A line of sources 100 blocks long: one group, cut until no axis is over 48
        int n = 51;
        int[] x = new int[n];
        int[] y = new int[n];
        int[] z = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = i * 2;
            y[i] = 64;
        }
        List<HeatClusters.Cluster> c = HeatClusters.cluster(x, y, z, 12, 48, -64, 319);
        assertTrue(c.size() >= 3, "cut into several: " + c.size());
        int members = 0;
        for (HeatClusters.Cluster a : c) {
            members += a.members().length;
            for (int k = 0; k < 3; k++) {
                assertTrue(a.box()[k + 3] - a.box()[k] + 1 <= 48, "no axis over 48");
            }
            for (HeatClusters.Cluster b : c) {
                if (a != b) {
                    boolean overlap = a.box()[0] <= b.box()[3] && b.box()[0] <= a.box()[3];
                    assertTrue(!overlap, "regions don't overlap");
                }
            }
        }
        assertEquals(n, members, "every source in exactly one region");
    }
}
