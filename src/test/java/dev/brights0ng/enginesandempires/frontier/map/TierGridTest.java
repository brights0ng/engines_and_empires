package dev.brights0ng.enginesandempires.frontier.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TierGridTest {

    @Test
    void packsFourToAByteAndBack() {
        byte[] tiers = {0, 1, 2, 3, 3, 2, 1};
        byte[] packed = TierGrid.pack(tiers);
        assertEquals(2, packed.length);
        assertArrayEquals(tiers, TierGrid.unpack(packed, tiers.length));
    }

    @Test
    void aBigSquarePacksToAQuarter() {
        byte[] tiers = new byte[193 * 193];
        for (int i = 0; i < tiers.length; i++) {
            tiers[i] = (byte) (i * 7 % 4);
        }
        byte[] packed = TierGrid.pack(tiers);
        assertEquals((tiers.length + 3) / 4, packed.length);
        assertArrayEquals(tiers, TierGrid.unpack(packed, tiers.length));
    }

    @Test
    void outlinesGoOnlyWhereTheNeighbourIsLower() {
        int edges = TierGrid.edges(2, 0, 2, 3, 1);
        assertTrue((edges & TierGrid.NORTH) != 0, "Frontier to the north");
        assertFalse((edges & TierGrid.SOUTH) != 0, "the same tier to the south");
        assertFalse((edges & TierGrid.WEST) != 0, "a higher tier to the west draws its own");
        assertTrue((edges & TierGrid.EAST) != 0, "Uninhabited to the east");
    }
}
