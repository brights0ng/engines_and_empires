package dev.brights0ng.paradynelib.heat.air;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The heat simulation's solver (weather phase 8f-1), including the calibration against Bright's timing (2026-10-15):
 * a 10x10x5 stone house with one campfire reaches its warmth in about 5 real minutes, and close to the fire it is felt
 * within seconds.
 */
class AirGridTest {

    private static final AirParams P = AirParams.DEFAULT;
    /** A campfire's power into the air. */
    private static final double CAMPFIRE = P.gridPower(1, 0.5);

    /** Space around the house, cells. */
    private static final int M = 6;
    /** The top of the ground (3 layers of dirt). */
    private static final int G = 3;

    /** A house built in a scene, and where its fire is. */
    private record House(AirGrid grid, int x0, int x1, int y0, int y1, int fx, int fy, int fz) {
        double interior() {
            return grid.meanAirOffset(x0 + 1, y0 + 1, x0 + 1, x1 - 1, y1 - 1, x1 - 1);
        }

        double nearFire() {
            return grid.offset(fx + 1, fy, fz);
        }
    }

    private enum Opening { NONE, WINDOW, MISSING_WALL }

    /**
     * A stone house of {@code size} x {@code size} x {@code height} blocks outside (1-block walls, floor and roof),
     * on dirt, its wooden door shut, a campfire in the middle of the floor, open sky around it.
     */
    private static House house(int size, int height, Opening opening) {
        int nx = size + 2 * M + 2;
        int ny = G + height + 8;
        AirGrid g = new AirGrid(nx, ny, nx, P);
        int x0 = M + 1;
        int x1 = M + size;
        int y0 = G + 1;
        int y1 = G + height;
        for (int x = 1; x < nx - 1; x++) {
            for (int z = 1; z < nx - 1; z++) {
                for (int y = 1; y < ny - 1; y++) {
                    if (y <= G) {
                        g.setSolid(x, y, z, 0.6, 0.8, 0);
                        continue;
                    }
                    boolean inX = x >= x0 && x <= x1;
                    boolean inZ = z >= x0 && z <= x1;
                    if (inX && inZ && y <= y1) {
                        boolean shell = x == x0 || x == x1 || z == x0 || z == x1 || y == y0 || y == y1;
                        if (shell) {
                            g.setSolid(x, y, z, 1, 1, 0);
                        } else {
                            g.setAir(x, y, z, false);
                        }
                    } else {
                        g.setAir(x, y, z, true);
                    }
                }
            }
        }
        int mid = (x0 + x1) / 2;
        g.setSolid(mid, y0 + 1, x0, 0.25, 0.5, 0);
        g.setSolid(mid, y0 + 2, x0, 0.25, 0.5, 0);
        if (opening == Opening.WINDOW) {
            g.setAir(x1, y0 + 2, mid, false);
        } else if (opening == Opening.MISSING_WALL) {
            for (int y = y0 + 1; y < y1; y++) {
                for (int z = x0 + 1; z < x1; z++) {
                    g.setAir(x1, y, z, false);
                }
            }
        }
        g.addSource(mid, y0 + 1, mid, CAMPFIRE, Double.NaN);
        return new House(g, x0, x1, y0, y1, mid, y0 + 1, mid);
    }

    private static House settled(int size, int height, Opening opening) {
        House h = house(size, height, opening);
        h.grid.settle(3000);
        return h;
    }

    @Test
    void aStoneHouseWithACampfireWarmsInAboutFiveMinutes() {
        House done = settled(10, 5, Opening.NONE);
        double target = done.interior();
        assertTrue(target > 10 && target < 14, "a campfire takes a closed stone house about +12 C: " + target);

        House h = house(10, 5, Opening.NONE);
        int t95 = -1;
        for (int s = 1; s <= 600 && t95 < 0; s++) {
            h.grid.step(1);
            if (h.interior() >= 0.95 * target) {
                t95 = s;
            }
        }
        assertTrue(t95 >= 240 && t95 <= 360, "95% of the warmth in 5 minutes (+-1): " + t95 + " s");
    }

    @Test
    void closeToTheFireItIsFeltWithinSeconds() {
        House done = settled(10, 5, Opening.NONE);
        double radiant = Radiant.warmth(P, 1, 0.5, 1, 1);
        double felt = radiant + done.nearFire();
        House h = house(10, 5, Opening.NONE);
        h.grid.step(15);
        assertTrue(radiant + h.nearFire() >= 0.5 * felt,
                "1 block from the fire, half of the final warmth within 15 s: " + (radiant + h.nearFire()) + " of " + felt);
        assertTrue(felt > done.interior() * 1.5, "noticeably warmer by the fire than the room: " + felt + " / " + done.interior());
        assertTrue(done.nearFire() > done.interior(), "the air by the fire is warmer too");
    }

    @Test
    void aWindowLeaksSomeAndAMissingWallMuch() {
        double sealed = settled(10, 5, Opening.NONE).interior();
        House window = settled(10, 5, Opening.WINDOW);
        House missing = settled(10, 5, Opening.MISSING_WALL);
        assertTrue(window.interior() < sealed && window.interior() > 0.85 * sealed,
                "a window loses a little: " + window.interior() + " of " + sealed);
        assertTrue(missing.interior() < 0.65 * sealed, "a missing wall loses much: " + missing.interior() + " of " + sealed);
        // Toward the window it is cooler than on the far side
        int mid = (window.x0 + window.x1) / 2;
        assertTrue(window.grid.offset(window.x1 - 1, window.y0 + 2, mid) < window.grid.offset(window.x0 + 1, window.y0 + 2, mid));
    }

    @Test
    void aBigHallWarmsFarLess() {
        double house = settled(10, 5, Opening.NONE).interior();
        double hall = settled(20, 8, Opening.NONE).interior();
        assertTrue(hall < 0.4 * house, "a 20x20x8 hall with one campfire: " + hall + " against " + house);
    }

    @Test
    void wallsHoldHeatAfterTheFireGoesOut() {
        House h = settled(10, 5, Opening.NONE);
        double warm = h.interior();
        h.grid.clearSources();
        h.grid.step(60);
        double air = h.interior();
        assertTrue(air < warm * 0.6 && air > warm * 0.2, "the air cools over a minute or so: " + air + " of " + warm);
        int mid = (h.x0 + h.x1) / 2;
        double wall = h.grid.offset(h.x0, h.y0 + 2, mid + 1);
        assertTrue(wall > 0.5, "the wall is still warm");
        h.grid.step(600);
        assertTrue(h.interior() < 0.1 * warm, "and in ten minutes it is nearly gone: " + h.interior());
    }

    @Test
    void longStepsLoseLittleQualityAndNeverBlowUp() {
        House fine = house(10, 5, Opening.NONE);
        for (int s = 0; s < 300; s++) {
            fine.grid.step(1);
        }
        House coarse = house(10, 5, Opening.NONE);
        for (int s = 0; s < 30; s++) {
            coarse.grid.step(10);
        }
        assertEquals(fine.interior(), coarse.interior(), 0.1 * fine.interior(), "300 s in 10 s steps vs 1 s steps");

        House huge = house(10, 5, Opening.NONE);
        huge.grid.step(5000);
        double target = settled(10, 5, Opening.NONE).interior();
        assertTrue(Double.isFinite(huge.interior()), "finite");
        assertEquals(target, huge.interior(), 0.05 * target, "one call for a very long time lands near the steady state");
    }

    @Test
    void settlingMatchesRunningItForLong() {
        House a = settled(10, 5, Opening.NONE);
        House b = house(10, 5, Opening.NONE);
        for (int s = 0; s < 1800; s++) {
            b.grid.step(1);
        }
        assertEquals(a.interior(), b.interior(), 0.02 * a.interior());
    }

    @Test
    void powerScalesTheWarmth() {
        House one = settled(10, 5, Opening.NONE);
        House two = house(10, 5, Opening.NONE);
        two.grid.clearSources();
        two.grid.addSource(two.fx, two.fy, two.fz, 2 * CAMPFIRE, Double.NaN);
        two.grid.settle(3000);
        assertEquals(2 * one.interior(), two.interior(), 0.1 * two.interior(), "nearly linear (warm air rising is not)");
    }

    /** Flat dirt ground, open sky, a campfire in the middle. */
    private static AirGrid openGround() {
        int n = 21;
        AirGrid g = new AirGrid(n, 12, n, P);
        for (int x = 1; x < n - 1; x++) {
            for (int z = 1; z < n - 1; z++) {
                for (int y = 1; y < 11; y++) {
                    if (y <= 3) {
                        g.setSolid(x, y, z, 0.6, 0.8, 0);
                    } else {
                        g.setAir(x, y, z, true);
                    }
                }
            }
        }
        g.addSource(10, 4, 10, CAMPFIRE, Double.NaN);
        return g;
    }

    @Test
    void inTheOpenOnlyThoseCloseByFeelIt() {
        AirGrid g = openGround();
        g.settle(1000);
        double one = g.offset(11, 4, 10);
        double four = g.offset(14, 4, 10);
        assertTrue(one > 1.5, "1 block away it is warmer: " + one);
        assertTrue(four < 0.3, "4 blocks away hardly at all: " + four);
        assertEquals(g.offset(9, 4, 10), one, 1e-6, "still air: the same all round");
        House inside = settled(10, 5, Opening.NONE);
        assertTrue(inside.nearFire() > 3 * one, "the same fire in a house is far warmer up close");
    }

    @Test
    void theWindCarriesItDownwind() {
        AirGrid g = openGround();
        g.setWind(5, 0);
        g.settle(1000);
        double down = g.offset(12, 4, 10);
        double up = g.offset(8, 4, 10);
        assertTrue(down > 4 * up, "2 blocks downwind against upwind: " + down + " / " + up);
        AirGrid still = openGround();
        still.settle(1000);
        AirGrid gale = openGround();
        gale.setWind(15, 0);
        gale.settle(1000);
        assertTrue(gale.offset(11, 4, 10) < g.offset(11, 4, 10), "a gale carries more away than a breeze");
        assertTrue(g.offset(9, 4, 10) < still.offset(9, 4, 10), "upwind is cooler than in still air");
    }

    @Test
    void warmAirRisesAndColdAirSinks() {
        House warm = settled(10, 5, Opening.NONE);
        int mid = (warm.x0 + warm.x1) / 2;
        double floor = warm.grid.offset(warm.x0 + 1, warm.y0 + 1, mid);
        double ceiling = warm.grid.offset(warm.x0 + 1, warm.y1 - 1, mid);
        assertTrue(ceiling > floor, "a heated room is warmer under the ceiling: " + ceiling + " / " + floor);

        House cold = house(10, 5, Opening.NONE);
        cold.grid.clearSources();
        cold.grid.addSource(cold.fx, cold.y1 - 1, cold.fz, -CAMPFIRE, Double.NaN);
        cold.grid.settle(3000);
        double cFloor = cold.grid.offset(cold.x0 + 1, cold.y0 + 1, mid);
        double cCeiling = cold.grid.offset(cold.x0 + 1, cold.y1 - 1, mid);
        assertTrue(cFloor < cCeiling, "a cooled room is colder at the floor: " + cFloor + " / " + cCeiling);
    }

    @Test
    void aSourceStopsAtItsLimit() {
        // A strong icebox on a 5 C day: limit 0 C is an offset of -5
        House h = house(10, 5, Opening.NONE);
        h.grid.clearSources();
        h.grid.addSource(h.fx, h.fy, h.fz, -10 * CAMPFIRE, -5);
        h.grid.settle(3000);
        assertTrue(h.grid.offset(h.fx, h.fy, h.fz) >= -5 - 1e-6, "never below freezing: " + h.grid.offset(h.fx, h.fy, h.fz));
        assertTrue(h.grid.offset(h.fx, h.fy, h.fz) < -4, "but close to it");
        for (int s = 0; s < 120; s++) {
            h.grid.step(1);
            assertTrue(h.grid.offset(h.fx, h.fy, h.fz) >= -5.05, "stepping too");
        }
    }

    @Test
    void theBoxFacesStayAtTheOutsideAir() {
        House h = settled(10, 5, Opening.NONE);
        assertEquals(0, h.grid.offset(0, 5, 5), 0);
        h.grid.setOffset(0, 5, 5, 3);
        assertEquals(0, h.grid.offset(0, 5, 5), 0, "a boundary cell can't be set");
        assertEquals(AirGrid.BOUNDARY, h.grid.kind(h.grid.nx() - 1, 5, 5));
    }

    @Test
    void anOpenSlabFaceLetsAirThrough() {
        // The window replaced by a slab-like block open on both sides along x: leaks like the window
        House window = settled(10, 5, Opening.WINDOW);
        House slab = house(10, 5, Opening.NONE);
        int mid = (slab.x0 + slab.x1) / 2;
        slab.grid.setSolid(slab.x1, slab.y0 + 2, mid, 1, 1, AirGrid.WEST | AirGrid.EAST);
        slab.grid.settle(3000);
        double sealed = settled(10, 5, Opening.NONE).interior();
        assertTrue(slab.interior() < sealed - 0.1, "it leaks: " + slab.interior() + " of " + sealed);
        assertEquals(window.interior(), slab.interior(), 0.05 * sealed, "about as much as an open window");
    }

    @Test
    void readingBetweenCellsUsesOnlyAir() {
        House h = settled(10, 5, Opening.NONE);
        int mid = (h.x0 + h.x1) / 2;
        // Halfway into the wall: only the inside air counts
        double inside = h.grid.offset(h.x0 + 1, h.y0 + 2, mid);
        assertEquals(inside, h.grid.offsetAt(h.x0 + 1.0, h.y0 + 2.5, mid + 0.5), 1e-9);
    }

    @Test
    void sweepsGrowWithTheStep() {
        AirGrid g = house(10, 5, Opening.NONE).grid;
        assertEquals(P.sweeps(), g.sweepsFor(1));
        assertEquals(P.sweeps(), g.sweepsFor(0.5));
        assertEquals((int) Math.ceil(P.sweeps() * Math.sqrt(10)), g.sweepsFor(10));
    }
}
