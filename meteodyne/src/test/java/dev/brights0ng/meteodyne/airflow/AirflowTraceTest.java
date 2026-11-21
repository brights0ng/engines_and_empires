// Written by Claude (GreedyFluids M8b b4: G2, the enclosed air a player is in).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dev.brights0ng.greedyfluids.grid.CellGrid3D.Side;

class AirflowTraceTest {

    private static final int[] SIZE = {64, 32, 64}, HOME = {0, 0, 0};

    /** Open inside the boxes given as {x0, y0, z0, x1, y1, z1} (inclusive), blocked elsewhere. */
    private static AirflowTrace.Space rooms(int[]... boxes) {
        return (x, y, z) -> {
            for (int[] b : boxes) {
                if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                    return AirflowTrace.OPEN;
                }
            }
            return AirflowTrace.BLOCKED;
        };
    }

    private static AirflowTrace run(int x, int y, int z, int reach, AirflowTrace.Space space) {
        AirflowTrace t = new AirflowTrace(x, y, z, reach, HOME, SIZE, space);
        assertTrue(t.advance(space, Long.MAX_VALUE));
        return t;
    }

    private static Set<String> reached(AirflowTrace t, int n) {
        List<AirflowNeighbourTiles.Reached> r = AirflowNeighbourTiles.reach(t.faces(), n);
        return r.stream().map(a -> a.dx() + "," + a.dy() + "," + a.dz()).collect(Collectors.toSet());
    }

    @Test
    void aRoomAcrossTheEastBorderCrossesItsWholeSection() {
        // inside x 59–68, y 10–13, z 20–24: 4 × 5 cells meet across x = 63 | 64
        AirflowTrace t = run(60, 10, 22, 32, rooms(new int[] {59, 10, 20, 68, 13, 24}));
        assertTrue(t.enclosed() && t.complete());
        assertEquals(10 * 4 * 5, t.walked());
        assertEquals(20, t.crossings(AirflowNeighbourTiles.faceKey(0, 0, 0, Side.EAST)));
        assertEquals(Set.of("1,0,0"), reached(t, 4));
    }

    @Test
    void aPlayerOutdoorsTracesNothing() {
        AirflowTrace t = run(5, 10, 5, 32, (x, y, z) -> AirflowTrace.BLOCKED);
        assertTrue(t.done() && !t.enclosed() && t.complete());
        assertEquals(Set.of(), reached(t, 1));
    }

    @Test
    void theWalkingLimitStopsAtItsDistance() {
        // a 1 × 1 tunnel along x from 40 to 100, the player at 40: the border at 63|64 is 23 steps away
        AirflowTrace.Space tunnel = rooms(new int[] {40, 10, 5, 100, 10, 5});
        AirflowTrace near = run(40, 10, 5, 32, tunnel);
        assertEquals(33, near.walked(), "the start and 32 steps");
        assertEquals(1, near.crossings(AirflowNeighbourTiles.faceKey(0, 0, 0, Side.EAST)));
        AirflowTrace short_ = run(40, 10, 5, 20, tunnel);
        assertEquals(0, short_.crossings(AirflowNeighbourTiles.faceKey(0, 0, 0, Side.EAST)), "20 steps fall short");
    }

    @Test
    void aShutDoorOrSolidWallStopsTheWalk() {
        // two rooms joined through x = 63 | 64, with the gap at x 62 closed (a door the host calls BLOCKED)
        AirflowTrace t = run(55, 10, 5, 32, rooms(new int[] {50, 10, 3, 61, 12, 7}, new int[] {63, 10, 3, 70, 12, 7}));
        assertEquals(0, t.crossings(AirflowNeighbourTiles.faceKey(0, 0, 0, Side.EAST)));
        assertEquals(Set.of(), reached(t, 1));
    }

    @Test
    void aCornerReachesTheDiagonalTile() {
        // a room around the x–z corner at (64, 64): inside x 60–67, z 60–67
        AirflowTrace t = run(61, 10, 61, 32, rooms(new int[] {60, 10, 60, 67, 11, 67}));
        assertEquals(Set.of("1,0,0", "0,0,1", "1,0,1"), reached(t, 4));
    }

    @Test
    void unknownCellsMarkTheWalkIncomplete() {
        AirflowTrace.Space space = (x, y, z) -> x >= 64 ? AirflowTrace.UNKNOWN
                : x >= 50 && x <= 70 && y == 10 && z == 5 ? AirflowTrace.OPEN : AirflowTrace.BLOCKED;
        AirflowTrace t = run(55, 10, 5, 32, space);
        assertFalse(t.complete());
        assertEquals(0, t.crossings(AirflowNeighbourTiles.faceKey(0, 0, 0, Side.EAST)));
    }

    @Test
    void aDeadlineResumesWhereItStopped() {
        AirflowTrace.Space hall = rooms(new int[] {30, 2, 30, 60, 20, 60});
        AirflowTrace whole = run(45, 10, 45, 32, hall);
        AirflowTrace parts = new AirflowTrace(45, 10, 45, 32, HOME, SIZE, hall);
        int calls = 0;
        while (!parts.advance(hall, 0)) { // a deadline already past: one batch of cells per call
            calls++;
        }
        assertTrue(calls > 1, "the hall takes several calls: " + calls);
        assertEquals(whole.walked(), parts.walked());
    }
}
