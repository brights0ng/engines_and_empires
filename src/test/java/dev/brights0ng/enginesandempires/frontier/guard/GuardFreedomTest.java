package dev.brights0ng.enginesandempires.frontier.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GuardFreedomTest {

    private static final int NEEDED = 64;
    private static final int LIMIT = 512;

    /** Flat ground at feet level 0, with walls (unstandable columns) where listed. */
    private static GuardFreedom.Ground flatWithWalls(Set<Long> walls) {
        return (x, y, z) -> y == 0 && !walls.contains(key(x, z));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static Set<Long> ring(int cx, int cz, int radius) {
        Set<Long> walls = new HashSet<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) == radius) {
                    walls.add(key(x, z));
                }
            }
        }
        return walls;
    }

    @Test
    void aGuardInOpenCountryIsFree() {
        assertTrue(GuardFreedom.isFree(flatWithWalls(Set.of()), 0, 0, 0, NEEDED, LIMIT));
    }

    @Test
    void aGolemInAThreeByThreePenIsNot() {
        // A fence ring of radius 2 leaves a 3x3 inside: 9 spots.
        GuardFreedom.Ground pen = flatWithWalls(ring(0, 0, 2));
        assertEquals(9, GuardFreedom.reachable(pen, 0, 0, 0, NEEDED, LIMIT));
        assertFalse(GuardFreedom.isFree(pen, 0, 0, 0, NEEDED, LIMIT));
    }

    @Test
    void anEightByEightYardIsJustEnough() {
        // A wall ring of radius... an 8x8 inside needs walls 4.5 out; use a 9x9 ring with a 7x7 inside (49) and a 10x10 one.
        assertFalse(GuardFreedom.isFree(flatWithWalls(ring(0, 0, 4)), 0, 0, 0, NEEDED, LIMIT), "7x7 = 49 spots");
        assertTrue(GuardFreedom.isFree(flatWithWalls(ring(0, 0, 5)), 0, 0, 0, NEEDED, LIMIT), "9x9 = 81 spots");
    }

    @Test
    void stepsUpAndDownCountButClimbingWallsDoesNot() {
        // A staircase: the ground rises one block per step along x.
        GuardFreedom.Ground stairs = (x, y, z) -> y == Math.max(0, x) && Math.abs(z) <= 1;
        assertTrue(GuardFreedom.reachable(stairs, 0, 0, 0, NEEDED, LIMIT) >= NEEDED, "a staircase can be walked");
        // A cliff: two blocks up in one step.
        GuardFreedom.Ground cliff = (x, y, z) -> Math.abs(z) <= 1 && ((x <= 0 && y == 0) || (x > 0 && y == 2)) && x > -3;
        assertEquals(9, GuardFreedom.reachable(cliff, 0, 0, 0, NEEDED, LIMIT), "only the low 3x3 is reachable");
    }

    @Test
    void standingOnSomethingOddStillFindsItsFeet() {
        GuardFreedom.Ground flat = flatWithWalls(Set.of());
        assertTrue(GuardFreedom.isFree(flat, 0, 1, 0, NEEDED, LIMIT), "feet one above the ground (on a slab)");
        assertEquals(0, GuardFreedom.reachable(flat, 0, 5, 0, NEEDED, LIMIT), "flying high: nowhere to stand");
    }

    @Test
    void theSearchStopsOnceItHasEnough() {
        assertEquals(NEEDED, GuardFreedom.reachable(flatWithWalls(Set.of()), 0, 0, 0, NEEDED, LIMIT));
    }
}
