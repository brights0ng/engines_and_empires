// Written by Claude.
package dev.brights0ng.meteodyne.heat.airgrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.brights0ng.meteodyne.heat.airgrid.CellCountStudy.Grid;
import dev.brights0ng.meteodyne.heat.airgrid.CellCountStudy.Partition;

class CellCountStudyTest {

    private static Grid filled(int x0, int y0, int z0, int sx, int sy, int sz, AirCellType type) {
        byte[] types = new byte[sx * sy * sz];
        Arrays.fill(types, (byte) type.ordinal());
        return new Grid(x0, y0, z0, sx, sy, sz, types, null);
    }

    /** A hollow box exactly filling the grid: 1-block SOLID walls, floor and ceiling; AIR inside. */
    static Grid ballroom(int x0, int y0, int z0, int sx, int sy, int sz) {
        Grid g = filled(x0, y0, z0, sx, sy, sz, AirCellType.AIR);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (x == 0 || y == 0 || z == 0 || x == sx - 1 || y == sy - 1 || z == sz - 1) {
                        g.types()[g.index(x, y, z)] = (byte) AirCellType.SOLID.ordinal();
                    }
                }
            }
        }
        return g;
    }

    private static int edgeAt(Partition p, int x, int y, int z) {
        int c = p.cellAt(x, y, z);
        return c < 0 ? 0 : p.dx(c);
    }

    @Test
    void emptyAirIsEightSectionCubesOrOneBox() {
        Grid g = filled(0, 0, 0, 32, 32, 32, AirCellType.AIR);
        Partition octree = CellCountStudy.octree(g, null, false);
        assertEquals(8, octree.count());
        for (int c = 0; c < octree.count(); c++) {
            assertEquals(16 * 16 * 16, octree.volume(c));
        }
        assertEquals(12, CellCountStudy.measure("octree", octree, 0).interfaces(), "a 2x2x2 block of cubes");
        assertEquals(8, CellCountStudy.octree(g, null, true).count(), "already balanced");
        Partition greedy = CellCountStudy.greedy(g, null, 0, 0);
        assertEquals(1, greedy.count());
        assertEquals(0, CellCountStudy.measure("greedy", greedy, 0).interfaces());
        CellCountStudy.Metrics uniform = CellCountStudy.uniform(g);
        assertEquals(32 * 32 * 32, uniform.cells());
        assertEquals(3L * 31 * 32 * 32, uniform.interfaces());
    }

    @Test
    void misalignedBallroomIsOneGreedyBox() {
        // 40 long (x), 20 tall (y), 25 wide (z), at world (5,0,3): every wall misses the 16-block grid.
        Grid g = ballroom(5, 0, 3, 40, 20, 25);
        Partition greedy = CellCountStudy.greedy(g, null, 0, 0);
        assertEquals(1, greedy.count());
        assertEquals(38 * 18 * 23, greedy.volume(0));
        assertEquals(AirCellType.AIR, greedy.type(0));
        assertTrue(CellCountStudy.octree(g, null, false).count() > 100, "the octree pays for the misalignment");
    }

    @Test
    void smallMisalignedRoomMatchesTheHandCount() {
        // Inside: x 6..13, y 1..4, z 4..7 (world). No aligned 4-cube fits in four rows of height; aligned 2-cubes fit
        // only in rows y 2..3: 4 (x) * 2 (z) = 8 of them. Rows y 1 and y 4 stay single blocks: 2 * 8 * 4 = 64.
        Grid g = ballroom(5, 0, 3, 10, 6, 6);
        Partition octree = CellCountStudy.octree(g, null, false);
        assertEquals(72, octree.count());
        assertEquals(72, CellCountStudy.octree(g, null, true).count(), "neighbours are one level apart already");
        assertEquals(8 * 4 * 4, CellCountStudy.uniform(g).cells());
        assertEquals(1, CellCountStudy.greedy(g, null, 0, 0).count());
    }

    @Test
    void wallInARowSplitsTheOctree() {
        // A 12-long, 4x4 row with a wall plane at x = 5.
        Grid g = filled(0, 0, 0, 12, 4, 4, AirCellType.AIR);
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                g.types()[g.index(5, y, z)] = (byte) AirCellType.SOLID.ordinal();
            }
        }
        Partition octree = CellCountStudy.octree(g, null, false);
        int[] edges = new int[12];
        for (int x = 0; x < 12; x++) {
            edges[x] = edgeAt(octree, x, 0, 0);
        }
        assertEquals("[4, 4, 4, 4, 1, 0, 2, 2, 4, 4, 4, 4]", Arrays.toString(edges), "4-cell / 1 / wall / 2-cell / 4-cell");
        assertEquals(1 + 16 + 4 + 1, octree.count());

        Partition balanced = CellCountStudy.octree(g, null, true);
        for (int x = 0; x < 12; x++) {
            edges[x] = edgeAt(balanced, x, 0, 0);
        }
        assertEquals("[2, 2, 2, 2, 1, 0, 2, 2, 4, 4, 4, 4]", Arrays.toString(edges), "the 4-cube next to single blocks splits");
        assertEquals(8 + 16 + 4 + 1, balanced.count());
    }

    @Test
    void balancingLeavesFaceNeighboursAtMostOneLevelApart() {
        for (long seed = 1; seed <= 6; seed++) {
            Random random = new Random(seed);
            Grid g = filled(-7 + (int) seed, 3, 11, 40, 36, 44, AirCellType.AIR);
            AirCellType[] mix = {AirCellType.SOLID, AirCellType.SOLID, AirCellType.FLUID, AirCellType.THIN, AirCellType.PERMEABLE};
            for (int k = 0; k < 60; k++) {
                // Sparse single blocks and a few small clumps, so big cells sit next to small ones.
                int x = random.nextInt(g.sx()), y = random.nextInt(g.sy()), z = random.nextInt(g.sz());
                int r = random.nextInt(3);
                byte t = (byte) mix[random.nextInt(mix.length)].ordinal();
                for (int yy = y; yy < Math.min(g.sy(), y + r + 1); yy++) {
                    for (int zz = z; zz < Math.min(g.sz(), z + r + 1); zz++) {
                        for (int xx = x; xx < Math.min(g.sx(), x + r + 1); xx++) {
                            g.types()[g.index(xx, yy, zz)] = t;
                        }
                    }
                }
            }
            Partition unbalanced = CellCountStudy.octree(g, null, false);
            Partition balanced = CellCountStudy.octree(g, null, true);
            assertCovers(g, unbalanced);
            assertCovers(g, balanced);
            assertTrue(worstLevelGap(g, unbalanced) > 1, "seed " + seed + " needs balancing");
            assertTrue(worstLevelGap(g, balanced) <= 1, "seed " + seed + " is balanced");
            assertTrue(balanced.count() >= unbalanced.count());
        }
    }

    @Test
    void activeVariantForcesSingleBlocksNearAMarkedPoint() {
        Grid g = filled(0, 0, 0, 48, 48, 48, AirCellType.AIR);
        int point = g.index(24, 24, 24);
        boolean[] forced = CellCountStudy.forcedNear(g, new int[] {point}, CellCountStudy.ACTIVE_RADIUS);
        Partition[] variants = {
                CellCountStudy.octree(g, forced, false),
                CellCountStudy.octree(g, forced, true),
                CellCountStudy.greedy(g, forced, 0, 0),
                CellCountStudy.greedy(g, forced, 16, 0),
                CellCountStudy.greedy(g, forced, 16, 4),
        };
        for (Partition p : variants) {
            assertCovers(g, p);
            for (int y = 0; y < 48; y++) {
                for (int z = 0; z < 48; z++) {
                    for (int x = 0; x < 48; x++) {
                        int d = Math.max(Math.abs(x - 24), Math.max(Math.abs(y - 24), Math.abs(z - 24)));
                        assertEquals(d <= 8, forced[g.index(x, y, z)]);
                        if (d <= 8) {
                            assertEquals(1, p.volume(p.cellAt(x, y, z)), "forced at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
            assertTrue(p.volume(p.cellAt(0, 0, 0)) > 1, "far blocks still merge");
        }
        CellCountStudy.Result result = CellCountStudy.run(g, new int[] {point});
        assertEquals(17L * 17 * 17, result.activeBlocks());
    }

    @Test
    void sampleChatOutput() {
        Grid g = ballroom(5, 0, 3, 40, 20, 25);
        CellCountStudy.Result result = CellCountStudy.run(g, new int[] {g.index(20, 1, 12)});
        assertEquals(11, result.chatLines().size(), "uniform, then octree, octree 2:1 and three greedy, each also active");
        assertEquals(38L * 18 * 23, result.uniformCells());
        assertEquals(40L * 20 * 25 - 38L * 18 * 23, result.typeCounts()[AirCellType.SOLID.ordinal()]);
        result.chatLines().forEach(System.out::println);
    }

    /** Every simulated block is in exactly one homogeneous cell; walls are in none. */
    private static void assertCovers(Grid g, Partition p) {
        long volume = 0;
        for (int c = 0; c < p.count(); c++) {
            volume += p.volume(c);
            for (int y = p.y(c); y < p.y(c) + p.dy(c); y++) {
                for (int z = p.z(c); z < p.z(c) + p.dz(c); z++) {
                    for (int x = p.x(c); x < p.x(c) + p.dx(c); x++) {
                        assertEquals(c, p.cellAt(x, y, z));
                        assertEquals(p.type(c).ordinal(), g.types()[g.index(x, y, z)]);
                    }
                }
            }
        }
        long simulated = 0;
        for (int i = 0; i < g.size(); i++) {
            if (g.simulated(i)) {
                simulated++;
            }
        }
        assertEquals(simulated, volume);
    }

    private static int worstLevelGap(Grid g, Partition p) {
        int worst = 0;
        for (int y = 0; y < g.sy(); y++) {
            for (int z = 0; z < g.sz(); z++) {
                for (int x = 0; x < g.sx(); x++) {
                    int a = p.cellAt(x, y, z);
                    if (a < 0) {
                        continue;
                    }
                    int[][] next = {{x + 1, y, z}, {x, y + 1, z}, {x, y, z + 1}};
                    for (int[] n : next) {
                        if (n[0] >= g.sx() || n[1] >= g.sy() || n[2] >= g.sz()) {
                            continue;
                        }
                        int b = p.cellAt(n[0], n[1], n[2]);
                        if (b >= 0 && b != a) {
                            worst = Math.max(worst, Math.abs(level(p.dx(a)) - level(p.dx(b))));
                        }
                    }
                }
            }
        }
        return worst;
    }

    private static int level(int edge) {
        return Integer.numberOfTrailingZeros(edge);
    }
}
