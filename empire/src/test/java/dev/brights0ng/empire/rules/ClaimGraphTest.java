// Written by Gemini; surroundingStaked by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ClaimGraphTest {

    @Test
    void isAdjacentChecksCardinalNeighboursOnly() {
        Set<Long> staked = new HashSet<>();
        assertFalse(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(0, 0)));

        staked.add(ChunkKeys.pack(0, 0));

        // 4 cardinal neighbours
        assertTrue(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(1, 0)));
        assertTrue(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(-1, 0)));
        assertTrue(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(0, 1)));
        assertTrue(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(0, -1)));

        // Diagonal or farther
        assertFalse(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(1, 1)));
        assertFalse(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(-1, -1)));
        assertFalse(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(2, 0)));
        assertFalse(ClaimGraph.isAdjacent(staked, ChunkKeys.pack(0, 0))); // Chunk itself is not adjacent to itself
    }

    @Test
    void lineBridgeCase() {
        // Line: (0,0) - (1,0) - (2,0)
        Set<Long> staked = new HashSet<>();
        long c0 = ChunkKeys.pack(0, 0);
        long c1 = ChunkKeys.pack(1, 0);
        long c2 = ChunkKeys.pack(2, 0);
        staked.add(c0);
        staked.add(c1);
        staked.add(c2);

        // Ends are leaves, not bridges
        assertFalse(ClaimGraph.isBridge(staked, c0));
        assertFalse(ClaimGraph.isBridge(staked, c2));

        // Middle chunk is a bridge
        assertTrue(ClaimGraph.isBridge(staked, c1));
    }

    @Test
    void ringBridgeCase() {
        // Ring:
        // (0,0) - (1,0)
        //   |       |
        // (0,1) - (1,1)
        Set<Long> staked = new HashSet<>();
        long c00 = ChunkKeys.pack(0, 0);
        long c10 = ChunkKeys.pack(1, 0);
        long c11 = ChunkKeys.pack(1, 1);
        long c01 = ChunkKeys.pack(0, 1);
        staked.add(c00);
        staked.add(c10);
        staked.add(c11);
        staked.add(c01);

        // In a simple loop, no chunk is a bridge
        assertFalse(ClaimGraph.isBridge(staked, c00));
        assertFalse(ClaimGraph.isBridge(staked, c10));
        assertFalse(ClaimGraph.isBridge(staked, c11));
        assertFalse(ClaimGraph.isBridge(staked, c01));
    }

    @Test
    void tShapeBridgeCase() {
        // T-shape:
        //       (1,0)
        //         |
        // (0,1)-(1,1)-(2,1)
        Set<Long> staked = new HashSet<>();
        long top = ChunkKeys.pack(1, 0);
        long center = ChunkKeys.pack(1, 1);
        long left = ChunkKeys.pack(0, 1);
        long right = ChunkKeys.pack(2, 1);
        staked.add(top);
        staked.add(center);
        staked.add(left);
        staked.add(right);

        // Leaves are not bridges
        assertFalse(ClaimGraph.isBridge(staked, top));
        assertFalse(ClaimGraph.isBridge(staked, left));
        assertFalse(ClaimGraph.isBridge(staked, right));

        // Junction is a bridge
        assertTrue(ClaimGraph.isBridge(staked, center));
    }

    @Test
    void smallSetBridgeCase() {
        Set<Long> staked = new HashSet<>();
        long c0 = ChunkKeys.pack(0, 0);
        staked.add(c0);
        assertFalse(ClaimGraph.isBridge(staked, c0));

        long c1 = ChunkKeys.pack(1, 0);
        staked.add(c1);
        assertFalse(ClaimGraph.isBridge(staked, c0));
        assertFalse(ClaimGraph.isBridge(staked, c1));
    }

    @Test
    void surroundingStakedNeedsAllNineChunks() {
        Set<Long> staked = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                staked.add(ChunkKeys.pack(5 + dx, -3 + dz));
            }
        }
        assertTrue(ClaimGraph.surroundingStaked(staked, ChunkKeys.pack(5, -3)));
        assertFalse(ClaimGraph.surroundingStaked(staked, ChunkKeys.pack(6, -3)));

        staked.remove(ChunkKeys.pack(4, -4));
        assertFalse(ClaimGraph.surroundingStaked(staked, ChunkKeys.pack(5, -3)));
        staked.add(ChunkKeys.pack(4, -4));
        staked.remove(ChunkKeys.pack(5, -3));
        assertFalse(ClaimGraph.surroundingStaked(staked, ChunkKeys.pack(5, -3)));
    }
}
