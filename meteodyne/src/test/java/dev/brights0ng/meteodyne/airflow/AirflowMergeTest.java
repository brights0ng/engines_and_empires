// Written by Claude (GreedyFluids M8b5b: tiles merged into one region).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AirflowMergeTest {

    /** Candidates by offset, with their links; the player's own tile is always in. */
    private static AirflowMerge.Tiles tiles(int[]... in) {
        Set<String> set = new HashSet<>();
        Map<String, Integer> links = new HashMap<>();
        set.add("0,0,0");
        for (int[] t : in) {
            String k = t[0] + "," + t[1] + "," + t[2];
            set.add(k);
            links.put(k, t.length > 3 ? t[3] : 0);
        }
        return new AirflowMerge.Tiles() {
            @Override
            public boolean in(int dx, int dy, int dz) {
                return set.contains(dx + "," + dy + "," + dz);
            }

            @Override
            public int link(int dx, int dy, int dz) {
                return links.getOrDefault(dx + "," + dy + "," + dz, 0);
            }
        };
    }

    @Test
    void theShapesAreBoxesOfTwoToFourTilesHoldingThePlayersInsideTheBlock() {
        assertEquals(21, AirflowMerge.SHAPES.size()); // 12 squares, 3 lines of 3, 6 lines of 2
        int last = Integer.MAX_VALUE;
        for (AirflowMerge.Box b : AirflowMerge.SHAPES) {
            assertTrue(b.holds(0, 0, 0), b + " holds the player's tile");
            assertTrue(b.tiles() >= 2 && b.tiles() <= AirflowMerge.MAX_TILES, b + " size");
            assertTrue(b.dx() >= -1 && b.dy() >= -1 && b.dz() >= -1 && b.dx() + b.sx() <= 2 && b.dy() + b.sy() <= 2
                    && b.dz() + b.sz() <= 2, b + " inside the block");
            assertTrue(b.tiles() <= last, "largest first");
            last = b.tiles();
        }
    }

    @Test
    void aTileAloneMergesWithNothing() {
        assertNull(AirflowMerge.choose(tiles(), null));
    }

    @Test
    void oneLinkedNeighbourMakesALineOfTwo() {
        assertEquals(new AirflowMerge.Box(0, 0, 0, 2, 1, 1), AirflowMerge.choose(tiles(new int[] {1, 0, 0, 20}), null));
        assertEquals(new AirflowMerge.Box(0, -1, 0, 1, 2, 1),
                AirflowMerge.choose(tiles(new int[] {0, -1, 0, 50}), null), "a cave cut by the band below");
    }

    @Test
    void anLTakesItsMoreStronglyLinkedArmAndTheRestStaysAcrossABorder() {
        AirflowMerge.Box b = AirflowMerge.choose(tiles(new int[] {1, 0, 0, 10}, new int[] {0, 0, 1, 40}), null);
        assertEquals(new AirflowMerge.Box(0, 0, 0, 1, 1, 2), b);
    }

    @Test
    void theLargestBoxWins() {
        AirflowMerge.Tiles square = tiles(new int[] {1, 0, 0, 5}, new int[] {0, 0, 1, 5}, new int[] {1, 0, 1, 5});
        assertEquals(new AirflowMerge.Box(0, 0, 0, 2, 1, 2), AirflowMerge.choose(square, null));
        AirflowMerge.Tiles line = tiles(new int[] {-1, 0, 0, 5}, new int[] {1, 0, 0, 5});
        assertEquals(new AirflowMerge.Box(-1, 0, 0, 3, 1, 1), AirflowMerge.choose(line, null));
    }

    @Test
    void theRunningBoxStaysOnATieAndGivesWayToALargerOneOrWhenItsTilesLeave() {
        AirflowMerge.Tiles l = tiles(new int[] {1, 0, 0, 10}, new int[] {0, 0, 1, 40});
        AirflowMerge.Box running = new AirflowMerge.Box(0, 0, 0, 2, 1, 1);
        assertEquals(running, AirflowMerge.choose(l, running), "no swap for an equal box");
        AirflowMerge.Tiles square = tiles(new int[] {1, 0, 0, 5}, new int[] {0, 0, 1, 5}, new int[] {1, 0, 1, 5});
        assertEquals(4, AirflowMerge.choose(square, running).tiles(), "a larger box takes over");
        AirflowMerge.Tiles south = tiles(new int[] {0, 0, 1, 40});
        assertEquals(new AirflowMerge.Box(0, 0, 0, 1, 1, 2), AirflowMerge.choose(south, running),
                "the running box's tile left the candidates");
    }
}
