// Written by Claude (GreedyFluids M8b b2); edited by Claude (M8b5b: the far side by position, regions of any size).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellGrid3D.Boundary;
import dev.brights0ng.greedyfluids.grid.CellGrid3D.FaceBoundary;
import dev.brights0ng.greedyfluids.grid.CellGrid3D.Side;
import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;
import dev.brights0ng.meteodyne.airflow.AirflowRegionModel.Weather;
import dev.brights0ng.greedyfluids.grid.Material;
import org.junit.jupiter.api.Test;

/** GreedyFluids M8b b2: a tile's enclosed cut cells exchange heat with the running neighbour, and nothing else. */
class AirflowBordersTest {

    private static final Cell AIR = new Cell(Kind.AIR, null);
    private static final Cell STONE = new Cell(Kind.SOLID, Material.STONE);

    @Test
    void enclosedCutCellsAreExchangeOnEverySide() {
        int nx = 4, ny = 4, nz = 4;
        int[] tops = new int[nx * nz];
        tops[0] = 2;  // column (0, 0): a roof at y 1
        tops[3] = 6;  // column (3, 0): a roof above the tile
        Set<String> got = new HashSet<>();
        for (FaceBoundary f : AirflowBorders.conditions(nx, ny, nz, tops)) {
            assertEquals(Boundary.EXCHANGE, f.boundary());
            got.add(f.side() + " " + f.x() + "," + f.y() + "," + f.z());
        }
        Set<String> want = new HashSet<>(List.of(
                "WEST 0,0,0", "WEST 0,1,0", "NORTH 0,0,0", "NORTH 0,1,0", "DOWN 0,0,0",
                "EAST 3,0,0", "EAST 3,1,0", "EAST 3,2,0", "EAST 3,3,0",
                "NORTH 3,0,0", "NORTH 3,1,0", "NORTH 3,2,0", "NORTH 3,3,0", "DOWN 3,0,0", "UP 3,3,0"));
        assertEquals(want, got);
        assertTrue(AirflowBorders.conditions(nx, ny, nz, null).isEmpty(), "no tops, no conditions");
    }

    @Test
    void newTopsChangeOnlyTheCellsWhoseEnclosureChanged() {
        int[] before = new int[16], after = new int[16];
        before[5] = 3; // column (1, 1): inside, touching only DOWN
        before[0] = 3;
        after[5] = 3; // unchanged: its DOWN cell stays EXCHANGE
        after[0] = 1; // column (0, 0): its roof lowered to y 0
        List<FaceBoundary> changes = AirflowBorders.changes(4, 4, 4, before, after);
        Set<String> got = new HashSet<>();
        for (FaceBoundary f : changes) {
            assertNull(f.boundary(), "back to the side's own");
            got.add(f.side() + " " + f.y());
        }
        assertEquals(Set.of("WEST 1", "WEST 2", "NORTH 1", "NORTH 2"), got);
        assertTrue(AirflowBorders.changes(4, 4, 4, after, after.clone()).isEmpty());
        assertEquals(AirflowBorders.conditions(4, 4, 4, after).size(),
                AirflowBorders.changes(4, 4, 4, after, null).size(), "no tops clears them all");
    }

    @Test
    void exchangeCellsListAGridsExchangeFaces() {
        CellGrid3D g = new CellGrid3D(4, 3, 5);
        g.setFaceBoundary(Side.EAST, 3, 2, 4, Boundary.EXCHANGE);
        g.setFaceBoundary(Side.EAST, 3, 0, 1, Boundary.EXCHANGE);
        g.setFaceBoundary(Side.UP, 2, 2, 3, Boundary.CLOSED);
        assertEquals(List.of(1, 14), Arrays.stream(AirflowBorders.exchangeCells(g, Side.EAST)).boxed().toList());
        assertEquals(0, AirflowBorders.exchangeCells(g, Side.UP).length);
    }

    @Test
    void theFarSideIsTheNeighboursAirAcrossOrNothing() {
        AirflowRegionModel west = tile(0, false);
        west.step();
        AirFrame f = west.publish();
        // from the east tile's WEST side: the west tile's cells at x 15; index y * nz + z
        float[] t = new float[16 * 16], d = new float[16 * 16];
        int i = 4 * 16 + 7;
        List<AirflowBorders.Beside> across = List.of(new AirflowBorders.Beside(f, -16, 0, 0));
        AirflowBorders.read(across, Side.WEST, i, 16, 16, 16, t, d);
        assertEquals(f.temperature(15.5, 4.5, 7.5), t[i], 1e-6);
        assertEquals(0.5 * f.extent(15.5, 4.5, 7.5, 0), d[i], 0, "half the far box's depth");
        AirflowBorders.read(across, Side.WEST, 7, 16, 16, 16, t, d);
        assertTrue(Float.isNaN(t[7]) && Float.isNaN(d[7]), "stone across: insulated");
        AirflowBorders.read(List.of(), Side.WEST, i, 16, 16, 16, t, d);
        assertTrue(Float.isNaN(t[i]), "none running");
        // M8b5b: regions of any size, found by position
        AirflowBorders.read(List.of(new AirflowBorders.Beside(f, -16, 16, 0)), Side.WEST, i, 16, 16, 16, t, d);
        assertTrue(Float.isNaN(t[i]), "a region across that doesn't hold the cell: insulated");
        float[] big = new float[32 * 16], bigD = new float[32 * 16];
        AirflowBorders.read(across, Side.WEST, i, 32, 16, 32, big, bigD); // index 71 on a 32-deep side: y 2, z 7
        assertEquals(f.temperature(15.5, 2.5, 7.5), big[i], 1e-6, "a region of another size reads by position");
    }

    /** Two tiles of one long room, cut between them: the heated one warms the other only while they run together. */
    @Test
    void aWarmNeighbourWarmsTheAirAcrossTheCutAndNothingElseDoes() {
        AirflowRegionModel west = tile(20000, true), east = tile(0, true);
        AirflowRegionModel westAlone = tile(20000, true), eastAlone = tile(0, true);
        AirFrame[] latest = {west.publish(), east.publish()};
        west.neighbours(side -> side == Side.EAST ? List.of(new AirflowBorders.Beside(latest[1], 16, 0, 0)) : List.of());
        east.neighbours(side -> side == Side.WEST ? List.of(new AirflowBorders.Beside(latest[0], -16, 0, 0)) : List.of());
        for (int i = 0; i < 480; i++) {
            west.step();
            latest[0] = west.publish();
            east.step();
            latest[1] = east.publish();
            westAlone.step();
            eastAlone.step();
        }
        double warmed = mean(east, 0, 3), alone = mean(eastAlone, 0, 3);
        assertEquals(10, alone, 1e-6, "with no neighbour the cut is insulated");
        assertTrue(warmed > 10.05, "the neighbour's heat came across: " + warmed);
        double out = west.exchangedJoules(), in = -east.exchangedJoules();
        assertTrue(out > 0 && in > 0, "out " + out + ", in " + in);
        assertEquals(out, in, 0.1 * out, "one conductance, each side's own flux a step apart");
        assertEquals(0, westAlone.exchangedJoules());
        assertTrue(mean(west, 12, 15) < mean(westAlone, 12, 15), "the heated side loses some to its neighbour");
    }

    /** A roof broken over the cut makes its cells OPEN again, with the next weather's tops. */
    @Test
    void newTopsFromTheWeatherChangeTheCut() {
        AirflowRegionModel m = tile(0, true);
        CellGrid3D g = m.region().grid();
        assertEquals(Boundary.EXCHANGE, g.faceBoundary(Side.EAST, 15, 3, 5));
        int[] tops = roofTops();
        for (int z = 0; z < 16; z++) {
            tops[z * 16 + 15] = 1; // the east column's roof gone: open above the floor
        }
        m.apply(List.of(new Weather(10, 0, 0, null, tops)));
        assertEquals(Boundary.OPEN, g.faceBoundary(Side.EAST, 15, 3, 5));
        assertEquals(Boundary.EXCHANGE, g.faceBoundary(Side.WEST, 0, 3, 5), "the other side keeps its roof");
        assertTrue(m.region().mesh().graph().exchangeCount() > 0, "the west cut still exchanges");
        m.step();
        assertTrue(m.healthy());
    }

    /** Stone floor at y 0 and roof at y 8 over air (y 1..7), air above the roof; a source at (8, 2, 8) if any. */
    private static AirflowRegionModel tile(double watts, boolean withTops) {
        byte[] cells = new byte[AirflowSections.SECTION_CELLS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (y == 0 || y == 8) {
                        cells[AirflowSections.Codes.index(x, y, z)] = 1;
                    }
                }
            }
        }
        AirflowSections.Codes codes = new AirflowSections.Codes(0, 1, AirflowCells.AIR, cells);
        AirflowCells.Table table = new AirflowCells.Table(1, new Cell[] {AIR, STONE});
        int[] at = watts == 0 ? new int[0] : new int[] {(2 * 16 + 8) * 16 + 8};
        double[] w = watts == 0 ? new double[0] : new double[] {watts};
        return AirflowRegionModel.build(new AirflowRegionModel.Spec(0, 0, 0, 16, 16, 16,
                new AirflowSections.Codes[] {codes}, table, 10, 0, 0, at, w, null, null, 0, null,
                withTops ? roofTops() : null));
    }

    private static int[] roofTops() {
        int[] tops = new int[16 * 16];
        Arrays.fill(tops, 9);
        return tops;
    }

    /** The mean air temperature under the roof over x0..x1, C. */
    private static double mean(AirflowRegionModel m, int x0, int x1) {
        double sum = 0;
        int n = 0;
        for (int y = 1; y < 8; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = x0; x <= x1; x++) {
                    sum += m.region().sampleT(x + 0.5, y + 0.5, z + 0.5);
                    n++;
                }
            }
        }
        return sum / n;
    }
}
