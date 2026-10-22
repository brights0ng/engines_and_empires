// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClaimGridTest {

    @Test
    void packAndUnpackRoundTrip() {
        byte[] original = new byte[]{0, 1, 2, 1, 2, 0, 1, 0, 2};
        byte[] packed = ClaimGrid.pack(original);
        assertEquals(3, packed.length);

        byte[] unpacked = ClaimGrid.unpack(packed, original.length);
        assertArrayEquals(original, unpacked);
    }

    @Test
    void edgesForUnclaimedColumnIsZero() {
        assertEquals(0, ClaimGrid.edges(ClaimGrid.UNCLAIMED, 1, 2, 1, 2));
    }

    @Test
    void edgesWhereAllNeighboursMatchIsZero() {
        assertEquals(0, ClaimGrid.edges(ClaimGrid.OUR_EMPIRE, ClaimGrid.OUR_EMPIRE, ClaimGrid.OUR_EMPIRE, ClaimGrid.OUR_EMPIRE, ClaimGrid.OUR_EMPIRE));
    }

    @Test
    void edgesWhereNeighboursDiffer() {
        int edges = ClaimGrid.edges(ClaimGrid.OUR_EMPIRE,
                ClaimGrid.UNCLAIMED, // North differs
                ClaimGrid.OUR_EMPIRE, // South matches
                ClaimGrid.OTHER_EMPIRE, // West differs
                ClaimGrid.OUR_EMPIRE); // East matches
        assertEquals(ClaimGrid.NORTH | ClaimGrid.WEST, edges);
    }

    @Test
    void isolatedClaimHasAllFourEdges() {
        int allEdges = ClaimGrid.edges(ClaimGrid.OUR_EMPIRE, 0, 0, 0, 0);
        assertEquals(ClaimGrid.NORTH | ClaimGrid.SOUTH | ClaimGrid.WEST | ClaimGrid.EAST, allEdges);
    }
}
