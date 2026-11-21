// Written by Claude (GreedyFluids M7d d1).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AirflowTilesTest {

    private static final int MIN = -64, MAX = 320;

    @Test
    void tilesAlignToMultiplesOf64AndBandsOf32() {
        assertArrayEquals(new int[] {0, 64, 0}, AirflowTiles.origin(MIN, MAX, 0, 64, 0));
        assertArrayEquals(new int[] {0, 32, 0}, AirflowTiles.origin(MIN, MAX, 63, 63, 63), "y = 63 is below the border");
        assertArrayEquals(new int[] {64, 64, 128}, AirflowTiles.origin(MIN, MAX, 64, 95, 130));
    }

    @Test
    void negativeCoordinatesRoundDown() {
        assertArrayEquals(new int[] {-64, -64, -128}, AirflowTiles.origin(MIN, MAX, -1, -64, -65));
        assertArrayEquals(new int[] {-64, 0, -64}, AirflowTiles.origin(MIN, MAX, -0.5, 0.2, -63.9));
    }

    @Test
    void heightsOutsideTheWorldClampToItsEndTiles() {
        assertEquals(-64, AirflowTiles.origin(MIN, MAX, 0, -200, 0)[1]);
        assertEquals(288, AirflowTiles.origin(MIN, MAX, 0, 319, 0)[1]);
        assertEquals(288, AirflowTiles.origin(MIN, MAX, 0, 500, 0)[1]);
        assertThrows(IllegalArgumentException.class, () -> AirflowTiles.origin(0, 16, 0, 0, 0));
    }

    @Test
    void bandsCountFromTheWorldsBottom() {
        assertEquals(-48, AirflowTiles.origin(-48, 208, 0, -48, 0)[1]);
        assertEquals(-16, AirflowTiles.origin(-48, 208, 0, 0, 0)[1], "a bottom off the 32 grid shifts the bands");
    }

    @Test
    void keysAreDistinctAcrossNeighboursAndSigns() {
        Set<Long> keys = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 9; y++) {
                for (int z = -2; z <= 2; z++) {
                    keys.add(AirflowTiles.key(64 * x, 32 * y, 64 * z));
                }
            }
        }
        assertEquals(5 * 12 * 5, keys.size());
        assertNotEquals(AirflowTiles.key(-29_999_936, 0, 0), AirflowTiles.key(29_999_936, 0, 0));
    }
}
