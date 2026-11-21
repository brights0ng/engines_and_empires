// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RegionFloodFillTest {

    @Test
    void fillsEnclosedBoxIncludingNegativeCoordinates() {
        // 5x2x5 box: x in [-2, 2], y in [-1, 0], z in [-2, 2] -> 50 blocks.
        RegionFloodFill.Result result = RegionFloodFill.fill(0, 0, 0, RegionRules.HOME_MAX_BLOCKS,
                (x, y, z) -> (x >= -2 && x <= 2 && y >= -1 && y <= 0 && z >= -2 && z <= 2)
                        ? RegionFloodFill.Cell.INCLUDE
                        : RegionFloodFill.Cell.BOUNDARY);

        assertTrue(result.succeeded());
        assertEquals(RegionInvalidReason.NONE, result.failure());
        assertEquals(50, result.blocks().size());
        assertTrue(result.blocks().contains(BlockKeys.pack(-2, -1, -2)));
        assertTrue(result.blocks().contains(BlockKeys.pack(2, 0, 2)));
        assertFalse(result.blocks().contains(BlockKeys.pack(3, 0, 0)));
    }

    @Test
    void fillsMultiRoomConnectedByDoorwayAndIgnoresDiagonals() {
        // Room A: x in [0, 1], y in [0, 1], z in [0, 1] (8 blocks)
        // Doorway: (2, 0, 0) and (2, 1, 0) (2 blocks)
        // Room B: x in [3, 4], y in [0, 1], z in [0, 1] (8 blocks)
        // Diagonal-only cell: (5, 0, 2) -> adjacent diagonally to (4, 0, 1), must NOT be reached.
        RegionFloodFill.Result result = RegionFloodFill.fill(0, 0, 0, 100, (x, y, z) -> {
            boolean roomA = x >= 0 && x <= 1 && y >= 0 && y <= 1 && z >= 0 && z <= 1;
            boolean door = x == 2 && (y == 0 || y == 1) && z == 0;
            boolean roomB = x >= 3 && x <= 4 && y >= 0 && y <= 1 && z >= 0 && z <= 1;
            boolean diag = x == 5 && y == 0 && z == 2;
            return (roomA || door || roomB || diag)
                    ? RegionFloodFill.Cell.INCLUDE
                    : RegionFloodFill.Cell.BOUNDARY;
        });

        assertTrue(result.succeeded());
        assertEquals(18, result.blocks().size());
        assertTrue(result.blocks().contains(BlockKeys.pack(4, 1, 1)));
        assertFalse(result.blocks().contains(BlockKeys.pack(5, 0, 2)));
    }

    @Test
    void exactCapSucceedsAndExceedingCapAbortsEarly() {
        // Exact cap of 10 along X axis [0..9].
        RegionFloodFill.Result exact = RegionFloodFill.fill(0, 0, 0, 10,
                (x, y, z) -> (x >= 0 && x < 10 && y == 0 && z == 0)
                        ? RegionFloodFill.Cell.INCLUDE
                        : RegionFloodFill.Cell.BOUNDARY);
        assertTrue(exact.succeeded());
        assertEquals(10, exact.blocks().size());

        // Infinite plane capped at 10 aborts with TOO_LARGE without exploding.
        AtomicInteger calls = new AtomicInteger();
        RegionFloodFill.Result over = RegionFloodFill.fill(0, 0, 0, 10, (x, y, z) -> {
            calls.incrementAndGet();
            return y == 0 ? RegionFloodFill.Cell.INCLUDE : RegionFloodFill.Cell.BOUNDARY;
        });
        assertFalse(over.succeeded());
        assertEquals(RegionInvalidReason.TOO_LARGE, over.failure());
        assertTrue(over.blocks().isEmpty());
        assertTrue(calls.get() < 100, "should abort early on cap, got " + calls.get() + " classifier calls");
    }

    @Test
    void hazardAtStartOrAdjacentAbortsImmediatelyWhileWalledHazardIsIgnored() {
        // Hazard at start.
        RegionFloodFill.Result atStart = RegionFloodFill.fill(0, 0, 0, 50,
                (x, y, z) -> RegionFloodFill.Cell.HAZARD);
        assertFalse(atStart.succeeded());
        assertEquals(RegionInvalidReason.HAZARD, atStart.failure());

        // Hazard 6-adjacent to interior cell (2, 0, 0) at (3, 0, 0).
        RegionFloodFill.Result adjacent = RegionFloodFill.fill(0, 0, 0, 50, (x, y, z) -> {
            if (x == 3 && y == 0 && z == 0) {
                return RegionFloodFill.Cell.HAZARD;
            }
            return (x >= 0 && x <= 2 && y == 0 && z == 0)
                    ? RegionFloodFill.Cell.INCLUDE
                    : RegionFloodFill.Cell.BOUNDARY;
        });
        assertFalse(adjacent.succeeded());
        assertEquals(RegionInvalidReason.HAZARD, adjacent.failure());

        // Hazard behind a wall (boundary at x=3, hazard at x=4) does not abort.
        RegionFloodFill.Result walled = RegionFloodFill.fill(0, 0, 0, 50, (x, y, z) -> {
            if (x == 4 && y == 0 && z == 0) {
                return RegionFloodFill.Cell.HAZARD;
            }
            return (x >= 0 && x <= 2 && y == 0 && z == 0)
                    ? RegionFloodFill.Cell.INCLUDE
                    : RegionFloodFill.Cell.BOUNDARY;
        });
        assertTrue(walled.succeeded());
        assertEquals(3, walled.blocks().size());
    }

    @Test
    void boundaryStartReturnsTooSmallAndNonPositiveCapReturnsTooLarge() {
        RegionFloodFill.Result startBoundary = RegionFloodFill.fill(0, 0, 0, 50,
                (x, y, z) -> RegionFloodFill.Cell.BOUNDARY);
        assertFalse(startBoundary.succeeded());
        assertEquals(RegionInvalidReason.TOO_SMALL, startBoundary.failure());

        RegionFloodFill.Result zeroCap = RegionFloodFill.fill(0, 0, 0, 0,
                (x, y, z) -> RegionFloodFill.Cell.INCLUDE);
        assertFalse(zeroCap.succeeded());
        assertEquals(RegionInvalidReason.TOO_LARGE, zeroCap.failure());
    }

    @Test
    void eachCoordinateIsClassifiedAtMostOnce() {
        Set<Long> seen = new HashSet<>();
        RegionFloodFill.Result result = RegionFloodFill.fill(0, 0, 0, 100, (x, y, z) -> {
            long key = BlockKeys.pack(x, y, z);
            assertTrue(seen.add(key), "classifier called twice for " + x + "," + y + "," + z);
            return (x >= 0 && x <= 2 && y >= 0 && y <= 1 && z >= 0 && z <= 2)
                    ? RegionFloodFill.Cell.INCLUDE
                    : RegionFloodFill.Cell.BOUNDARY;
        });
        assertTrue(result.succeeded());
        assertEquals(18, result.blocks().size());
    }
}
