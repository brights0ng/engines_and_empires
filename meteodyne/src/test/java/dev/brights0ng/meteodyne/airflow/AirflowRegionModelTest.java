// Written by Claude (GreedyFluids M7b b3).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellType;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.meteodyne.airflow.AirflowBatch.Change;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;
import dev.brights0ng.meteodyne.airflow.AirflowRegionModel.SourceChange;
import dev.brights0ng.meteodyne.airflow.AirflowRegionModel.Weather;
import org.junit.jupiter.api.Test;

/** GreedyFluids M7b b3: a real region built from section codes, fed edits, doors, sources and weather. */
class AirflowRegionModelTest {

    static final Cell AIR = new Cell(Kind.AIR, null);
    static final Cell STONE = new Cell(Kind.SOLID, Material.STONE);
    static final Cell SHUT = new Cell(Kind.DOOR_SHUT, Material.DOOR);
    static final Cell OPEN = new Cell(Kind.DOOR_OPEN, Material.DOOR);

    /** One 16³ section: a stone floor at y 0, air above; sources as region cell index → W. */
    private static AirflowRegionModel model(Map<Integer, Double> sources) {
        byte[] cells = new byte[AirflowSections.SECTION_CELLS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                cells[AirflowSections.Codes.index(x, 0, z)] = 1;
            }
        }
        AirflowSections.Codes codes = new AirflowSections.Codes(0, 1, AirflowCells.AIR, cells);
        AirflowCells.Table table = new AirflowCells.Table(1, new Cell[] {AIR, STONE});
        int[] at = sources.keySet().stream().mapToInt(Integer::intValue).toArray();
        double[] w = new double[at.length];
        for (int i = 0; i < at.length; i++) {
            w[i] = sources.get(at[i]);
        }
        return AirflowRegionModel.build(new AirflowRegionModel.Spec(0, 0, 0, 16, 16, 16,
                new AirflowSections.Codes[] {codes}, table, 10, 0, 0, at, w));
    }

    private static int cell(int x, int y, int z) {
        return (y * 16 + z) * 16 + x;
    }

    @Test
    void buildsTheGridFromCodesAndSteps() {
        AirflowRegionModel m = model(Map.of());
        CellGrid3D g = m.region().grid();
        assertEquals(CellType.SOLID, g.type(3, 0, 3));
        assertEquals(Material.STONE, g.material(3, 0, 3));
        assertEquals(CellType.AIR, g.type(3, 1, 3));
        assertTrue(m.boxes() > 0);
        m.step();
        assertEquals(0.5, m.time(), 1e-12);
        assertTrue(m.healthy());
        assertTrue(m.publish() != null);
    }

    @Test
    void editsApplyAndMismatchesAreDesyncs() {
        AirflowRegionModel m = model(Map.of());
        CellGrid3D g = m.region().grid();
        m.apply(List.of(new Change(5, 1, 5, AIR, STONE), new Change(3, 0, 3, STONE, AIR)));
        assertEquals(CellType.SOLID, g.type(5, 1, 5));
        assertEquals(CellType.AIR, g.type(3, 0, 3));
        assertEquals(0, m.desyncs());
        m.apply(List.of(new Change(5, 1, 5, AIR, STONE)));
        assertEquals(1, m.desyncs(), "placing into a solid cell is skipped");
        m.step();
        assertTrue(m.healthy());
    }

    @Test
    void doorsArePlacedToggledAndRemoved() {
        AirflowRegionModel m = model(Map.of());
        CellGrid3D g = m.region().grid();
        m.apply(List.of(new Change(8, 1, 8, AIR, SHUT)));
        assertEquals(CellType.DOOR, g.type(8, 1, 8));
        assertFalse(g.doorOpen(8, 1, 8));
        m.apply(List.of(new Change(8, 1, 8, SHUT, OPEN)));
        assertTrue(g.doorOpen(8, 1, 8));
        m.apply(List.of(new Change(9, 1, 9, SHUT, OPEN)));
        assertEquals(1, m.desyncs(), "a toggle on a cell that isn't a door");
        m.apply(List.of(new Change(8, 1, 8, OPEN, STONE)));
        assertEquals(CellType.SOLID, g.type(8, 1, 8), "door to solid in two batches");
        m.step();
        assertTrue(m.healthy());
    }

    @Test
    void sourcesFollowThePowerRule() {
        AirflowRegionModel m = model(Map.of(cell(4, 4, 4), 1000.0));
        assertEquals(Map.of(cell(4, 4, 4), 1000.0), m.appliedSources(), "a source in air keeps its power");
        m.apply(List.of(new SourceChange(2, 0, 2, 600)));
        assertEquals(600, m.appliedSources().get(cell(2, 1, 2)), 1e-12, "a floor source heats the air above it");
        m.apply(List.of(new Change(2, 1, 2, AIR, STONE)));
        assertFalse(m.appliedSources().containsKey(cell(2, 1, 2)), "covered: no air face, no power");
        m.apply(List.of(new Change(8, 1, 8, AIR, STONE), new SourceChange(8, 1, 8, 500)));
        for (int[] f : new int[][] {{7, 1, 8}, {9, 1, 8}, {8, 2, 8}, {8, 1, 7}, {8, 1, 9}}) {
            assertEquals(100, m.appliedSources().get(cell(f[0], f[1], f[2])), 1e-12, "split over five air faces");
        }
        m.apply(List.of(new SourceChange(4, 4, 4, 0)));
        assertFalse(m.appliedSources().containsKey(cell(4, 4, 4)), "power 0 removes the source");
        m.step();
        assertTrue(m.healthy());
    }

    @Test
    void weatherSetsTheBackgroundWithSkyFollowWeights() {
        AirflowRegionModel m = model(Map.of());
        int open = m.region().grid().index(5, 5, 5);
        assertEquals(1, m.region().background().follow(open), 0, "air under open sky follows");
        assertEquals(0, m.region().background().follow(m.region().grid().index(5, 0, 5)), 0, "the floor doesn't");
        m.apply(List.of(new Weather(12, 1, 0)));
        assertEquals(12, m.region().background().reference(), 1e-12);
        m.apply(List.of(new Change(5, 10, 5, AIR, STONE), new Weather(12, 1, 0)));
        assertEquals(0, m.region().background().follow(open), 0, "a roof ends following, at the next weather");
        m.step();
        assertTrue(m.healthy());
    }
}
