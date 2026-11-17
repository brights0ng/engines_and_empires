// Written by Claude (GreedyFluids M8b b4: B3's reach).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dev.brights0ng.greedyfluids.grid.CellGrid3D.Side;

class AirflowNeighbourTilesTest {

    /** Faces of the block with a count each (by the lower tile and axis); every other face 0. */
    private static final class Block implements AirflowNeighbourTiles.Faces {
        final Map<Integer, Integer> counts = new HashMap<>();
        final java.util.Set<Integer> distinct = new java.util.HashSet<>();
        int asked;

        Block link(int dx, int dy, int dz, Side side, int count) {
            counts.put(AirflowNeighbourTiles.faceKey(dx, dy, dz, side), count);
            return this;
        }

        @Override
        public int shared(int dx, int dy, int dz, Side side) {
            asked++;
            distinct.add(AirflowNeighbourTiles.faceKey(dx, dy, dz, side));
            return counts.getOrDefault(AirflowNeighbourTiles.faceKey(dx, dy, dz, side), 0);
        }
    }

    private static Set<String> tiles(List<AirflowNeighbourTiles.Reached> reached) {
        return reached.stream().map(r -> r.dx() + "," + r.dy() + "," + r.dz()).collect(Collectors.toSet());
    }

    @Test
    void aFaceLinksAtTheThresholdAndNotBelowIt() {
        Block b = new Block().link(0, 0, 0, Side.EAST, 4);
        assertEquals(Set.of("1,0,0"), tiles(AirflowNeighbourTiles.reach(b, 4)));
        assertEquals(Set.of(), tiles(AirflowNeighbourTiles.reach(b, 5)));
        assertEquals(4, AirflowNeighbourTiles.reach(b, 2).get(0).link());
        assertEquals(Set.of(), tiles(AirflowNeighbourTiles.reach(new Block(), 1)), "nothing shared, nothing reached");
    }

    @Test
    void aFaceIsTheSameFromEitherSide() {
        assertEquals(AirflowNeighbourTiles.faceKey(0, 0, 0, Side.EAST), AirflowNeighbourTiles.faceKey(1, 0, 0, Side.WEST));
        assertEquals(AirflowNeighbourTiles.faceKey(0, 0, 0, Side.DOWN), AirflowNeighbourTiles.faceKey(0, -1, 0, Side.UP));
        assertEquals(AirflowNeighbourTiles.faceKey(0, 0, 0, Side.SOUTH), AirflowNeighbourTiles.faceKey(0, 0, 1, Side.NORTH));
    }

    @Test
    void aCornerReachesTheDiagonalThroughEitherNeighbour() {
        // a house at an x–z corner: the player's quarter shares with east and south; those share with the diagonal
        Block b = new Block().link(0, 0, 0, Side.EAST, 20).link(0, 0, 0, Side.SOUTH, 20)
                .link(1, 0, 0, Side.SOUTH, 20).link(0, 0, 1, Side.EAST, 20);
        List<AirflowNeighbourTiles.Reached> r = AirflowNeighbourTiles.reach(b, 4);
        assertEquals(Set.of("1,0,0", "0,0,1", "1,0,1"), tiles(r));
        assertEquals("1,0,1", r.get(2).dx() + "," + r.get(2).dy() + "," + r.get(2).dz(), "face neighbours come first");
        // the diagonal through one side only still counts
        Block one = new Block().link(0, 0, 0, Side.EAST, 20).link(1, 0, 0, Side.SOUTH, 20);
        assertEquals(Set.of("1,0,0", "1,0,1"), tiles(AirflowNeighbourTiles.reach(one, 4)));
    }

    @Test
    void theFloodNeverLeavesTheBlock() {
        // a long hall along x: every face along it shares (the block's two, and the world's further on, which the
        // flood never asks for), but only the tiles one step away run
        Block b = new Block().link(-1, 0, 0, Side.EAST, 30).link(0, 0, 0, Side.EAST, 30);
        assertEquals(Set.of("1,0,0", "-1,0,0"), tiles(AirflowNeighbourTiles.reach(b, 4)));
        // a tall shaft through bands: the band above and below
        Block shaft = new Block().link(0, 0, 0, Side.UP, 9).link(0, 0, 0, Side.DOWN, 9);
        assertEquals(Set.of("0,1,0", "0,-1,0"), tiles(AirflowNeighbourTiles.reach(shaft, 4)));
        // whatever is asked lies inside the block: two tiles of it on either side
        AirflowNeighbourTiles.Faces strict = (dx, dy, dz, side) -> {
            int a = side.axis(), d = side.high() ? 1 : -1;
            int[] t = {dx, dy, dz};
            t[a] += d;
            assertTrue(Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1
                    && Math.abs(t[0]) <= 1 && Math.abs(t[1]) <= 1 && Math.abs(t[2]) <= 1, "asked outside the block");
            return 50;
        };
        assertEquals(26, AirflowNeighbourTiles.reach(strict, 4).size(), "everything shares: the whole block, no more");
    }

    @Test
    void unknownFacesLinkNothing() {
        Block b = new Block().link(0, 0, 0, Side.EAST, -1).link(0, 0, 0, Side.NORTH, 6);
        assertEquals(Set.of("0,0,-1"), tiles(AirflowNeighbourTiles.reach(b, 1)));
    }

    @Test
    void theMemoAsksEachFaceOnceAcrossThresholds() {
        Block b = new Block().link(0, 0, 0, Side.EAST, 20).link(0, 0, 0, Side.SOUTH, 3)
                .link(1, 0, 0, Side.SOUTH, 20).link(0, 0, 1, Side.EAST, 20);
        AirflowNeighbourTiles.Faces memo = AirflowNeighbourTiles.memo(b);
        assertEquals(Set.of("1,0,0", "0,0,1", "1,0,1"), tiles(AirflowNeighbourTiles.reach(memo, 2)));
        assertEquals(Set.of("1,0,0", "1,0,1", "0,0,1"), tiles(AirflowNeighbourTiles.reach(memo, 4)),
                "at 4 the south face is too small, but the diagonal reaches south through it");
        assertEquals(Set.of("1,0,0", "1,0,1", "0,0,1"), tiles(AirflowNeighbourTiles.reach(memo, 8)));
        assertEquals(b.distinct.size(), b.asked, "no face was asked twice, from either side or at any threshold");
        assertTrue(b.asked <= 54, "at most the block's 54 inner faces: " + b.asked);
    }
}
