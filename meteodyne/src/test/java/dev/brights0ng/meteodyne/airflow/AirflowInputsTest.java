// Written by Claude (GreedyFluids M7c; the outside air ahead of an edit, M7f); edited by Claude (M8a: the hot wall's
// test runs 2 min, as the realistic surface film slows its heat into the room).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.meteodyne.airflow.AirflowBatch.Change;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;
import dev.brights0ng.meteodyne.airflow.AirflowRegionModel.Bodies;
import dev.brights0ng.meteodyne.airflow.AirflowRegionModel.SourceChange;
import dev.brights0ng.meteodyne.airflow.AirflowRegionModel.Weather;
import org.junit.jupiter.api.Test;

/** M7c: the region model's physics inputs: the background lattice and sky follow, hot walls, thermostats, body heat. */
class AirflowInputsTest {

    static final Cell AIR = new Cell(Kind.AIR, null);
    static final Cell STONE = new Cell(Kind.SOLID, Material.STONE);
    static final Cell LAVA = new Cell(Kind.SOLID, Material.STONE, 300);

    /** One 16³ section, solid stone but for a closed 6×4×6 room at x, z 5..10 and y 1..4; code 2 is a hot wall. */
    private static AirflowRegionModel room(float[] lattice, int[] tops, int[] sourceCells, double[] watts,
                                           double[] limits, int hotCell) {
        byte[] cells = new byte[AirflowSections.SECTION_CELLS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    boolean inside = x >= 5 && x <= 10 && y >= 1 && y <= 4 && z >= 5 && z <= 10;
                    cells[AirflowSections.Codes.index(x, y, z)] = (byte) (inside ? 0 : 1);
                }
            }
        }
        if (hotCell >= 0) {
            cells[hotCell] = 2;
        }
        AirflowSections.Codes codes = new AirflowSections.Codes(0, 1, AirflowCells.AIR, cells);
        AirflowCells.Table table = new AirflowCells.Table(1, new Cell[] {AIR, STONE, LAVA});
        return AirflowRegionModel.build(new AirflowRegionModel.Spec(0, 0, 0, 16, 16, 16,
                new AirflowSections.Codes[] {codes}, table, 10, 0, 0, sourceCells, watts, limits, null, 0, lattice,
                tops));
    }

    private static int cell(int x, int y, int z) {
        return (y * 16 + z) * 16 + x;
    }

    private static float[] lattice(float t) {
        float[] l = new float[5 * 5 * 5];
        Arrays.fill(l, t);
        return l;
    }

    @Test
    void theLatticeSetsAPerCellBackgroundAndTheRoofStopsFollowing() {
        float[] l = lattice(10);
        for (int j = 0; j < 5; j++) {
            for (int i = 0; i < 25; i++) {
                l[j * 25 + i] = 10 - j; // 1 C colder every 4 blocks up
            }
        }
        int[] tops = new int[256];
        Arrays.fill(tops, 16); // the whole section is under cover
        AirflowRegionModel m = room(l, tops, new int[0], new double[0], null, -1);
        var bg = m.region().background();
        assertEquals(10 - 1.5 / 4, bg.cell(cell(7, 1, 7)), 1e-5, "interpolated at the cell's centre");
        assertEquals(0, bg.follow(cell(7, 1, 7)), 0, "a closed room under cover doesn't follow");
        m.apply(List.of(new Weather(12, 0, 0, lattice(12), tops)));
        assertEquals(12, m.region().background().cell(cell(7, 1, 7)), 1e-6, "a new lattice replaces the field");
        m.step();
        assertTrue(m.healthy());
    }

    @Test
    void aHotWallHeatsTheRoomAndGoesWhenItTurnsToStone() {
        AirflowRegionModel m = room(lattice(10), null, new int[0], new double[0], null, cell(7, 0, 7));
        // M8a: through still-air films (0.13 m²·K/W) the 300 C block gives about 2 kW; 30 s warmed the point 0.33 C
        for (int i = 0; i < 240; i++) {
            m.step();
        }
        double warm = m.region().sampleT(7.5, 2.5, 7.5);
        assertTrue(warm > 10.5, "the room warms over the hot floor block: " + warm);
        assertEquals(300, m.region().thermalState().solid(cell(7, 0, 7)), 1e-6, "the wall stays at 300 C");
        m.apply(List.of(new Change(7, 0, 7, LAVA, STONE))); // lava turned to obsidian
        assertTrue(Double.isNaN(m.region().grid().fixedTemperature(cell(7, 0, 7))), "a material change frees it");
        m.apply(List.of(new Change(8, 0, 8, STONE, LAVA)));
        assertEquals(300, m.region().grid().fixedTemperature(cell(8, 0, 8)), 0, "and makes another hot");
        assertEquals(0, m.desyncs());
        m.step();
        assertTrue(m.healthy());
    }

    @Test
    void aLimitedCoolerSwitchesOffAtItsLimit() {
        int cooler = cell(7, 1, 7);
        AirflowRegionModel m = room(lattice(10), null, new int[] {cooler}, new double[] {-20_000},
                new double[] {5}, -1);
        assertEquals(-20_000, m.appliedSources().get(cooler), 0);
        int steps = 0;
        while (m.appliedSources().containsKey(cooler) && steps < 400) {
            m.step();
            steps++;
        }
        assertFalse(m.appliedSources().containsKey(cooler), "off once its air reaches 5 C (after " + steps + " steps)");
        assertTrue(m.region().sampleT(7.5, 1.5, 7.5) <= 5.5, "near the limit: " + m.region().sampleT(7.5, 1.5, 7.5));
        m.apply(List.of(new SourceChange(7, 1, 7, 0)));
        assertTrue(m.appliedSources().isEmpty());
    }

    @Test
    void bodyHeatWarmsASmallRoom() {
        AirflowRegionModel m = room(lattice(10), null, new int[0], new double[0], null, -1);
        m.apply(List.of(new Bodies(new double[] {7.5, 1.9, 7.5, AirflowSources.PLAYER_WATTS})));
        for (int i = 0; i < 120; i++) {
            m.step();
        }
        double t = m.region().sampleT(8.5, 3.5, 8.5);
        assertTrue(t > 10.02, "a player's 500 W warms a closed 6×4×6 room in a minute: " + t);
        assertTrue(m.healthy());
    }

    /** As {@link #room} with no sources, but open air above y 5 (column tops 6): a closed room under a stone roof. */
    private static AirflowRegionModel roofedRoom(int[] tops) {
        byte[] cells = new byte[AirflowSections.SECTION_CELLS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    boolean inside = x >= 5 && x <= 10 && y >= 1 && y <= 4 && z >= 5 && z <= 10;
                    cells[AirflowSections.Codes.index(x, y, z)] = (byte) (inside || y >= 6 ? 0 : 1);
                }
            }
        }
        AirflowSections.Codes codes = new AirflowSections.Codes(0, 1, AirflowCells.AIR, cells);
        AirflowCells.Table table = new AirflowCells.Table(1, new Cell[] {AIR, STONE, LAVA});
        return AirflowRegionModel.build(new AirflowRegionModel.Spec(0, 0, 0, 16, 16, 16,
                new AirflowSections.Codes[] {codes}, table, 10, 0, 0, new int[0], new double[0], null, null, 0,
                lattice(10), tops));
    }

    @Test
    void theOutsideAirSentAheadOfAnEditShiftsWithTheFollowWeightsBeforeIt() {
        int[] tops = new int[256];
        Arrays.fill(tops, 6);
        double[] room = new double[2];
        for (int order = 0; order < 2; order++) {
            AirflowRegionModel m = roofedRoom(tops);
            Change hole = new Change(7, 5, 7, STONE, AIR); // a hole in the roof lets the sky into the room
            Weather warmer = new Weather(14, 0, 0, lattice(14), tops);
            m.apply(order == 0 ? List.<Object>of(warmer, hole) : List.<Object>of(hole, warmer));
            m.apply(List.of(warmer)); // the manager's next check: the new follow weights, nothing left to shift
            room[order] = m.region().sampleT(7.5, 1.5, 7.5);
        }
        assertEquals(10, room[0], 0.25, "warmed before the hole was made: the closed room doesn't follow");
        assertTrue(room[1] > 10.5, "the same warming applied after it would have moved the room: " + room[1]);
    }
}
