// Written by Claude (GreedyFluids M7b b3); edited by Claude (M7d d2: save and restore; M7f: stores one by one; M8c c3:
// the source-velocity radius from the spec).
// Edited by Claude: temperatures out of the plausibility band are a runaway (2026-11-21).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellType;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.greedyfluids.refine.AdaptiveMesh3D;
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
        return model(sources, false, null, 0);
    }

    /**
     * As above, or (with {@code room}) solid stone but for a closed 4×4×4 room at x, z 6..9, y 1..4; built from saved
     * sections decayed to tick {@code now}.
     */
    private static AirflowRegionModel model(Map<Integer, Double> sources, boolean room, AirflowSaved.Section[] saved,
            long now) {
        byte[] cells = new byte[AirflowSections.SECTION_CELLS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    boolean inside = x >= 6 && x <= 9 && y >= 1 && y <= 4 && z >= 6 && z <= 9;
                    if (y == 0 || (room && !inside)) {
                        cells[AirflowSections.Codes.index(x, y, z)] = 1;
                    }
                }
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
                new AirflowSections.Codes[] {codes}, table, 10, 0, 0, at, w, saved, now));
    }

    private static int cell(int x, int y, int z) {
        return (y * 16 + z) * 16 + x;
    }

    /** 2026-11-21: air or a wall out of AirflowPlausibility's band is a runaway, though every value is finite. */
    @Test
    void implausibleTemperaturesAreARunaway() {
        AirflowRegionModel m = model(Map.of(), true, null, 0);
        m.step();
        assertNull(m.problem());
        var s = m.region().mesh().solver();
        s.setTemperature(0, 1e12);
        assertFalse(m.healthy());
        assertTrue(m.problem().startsWith("air at"), m.problem());
        s.setTemperature(0, 10);
        assertNull(m.problem());
        var w = s.wallStore();
        assertTrue(w.count() > 0 && !w.isFixed(0), "the closed room has wall stores");
        w.setTemperature(0, -1e6);
        assertTrue(m.problem().startsWith("a wall at"), m.problem());
        w.setTemperature(0, AirflowPlausibility.HIGH_C);
        assertNull(m.problem(), "the band's edge is in it");
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
    void theSpecSetsTheSourceVelocityRadius() {
        AdaptiveMesh3D mesh = model(Map.of()).region().mesh();
        assertEquals(AdaptiveMesh3D.GateMode.CONTRAST, mesh.gateMode());
        assertEquals(AdaptiveMesh3D.DEFAULT_SOURCE_VELOCITY_RADIUS, mesh.sourceVelocityRadius(), "the default");
        AirflowSections.Codes air = new AirflowSections.Codes(0, 1, AirflowCells.AIR,
                new byte[AirflowSections.SECTION_CELLS]);
        AirflowRegionModel four = AirflowRegionModel.build(new AirflowRegionModel.Spec(0, 0, 0, 16, 16, 16,
                new AirflowSections.Codes[] {air}, new AirflowCells.Table(1, new Cell[] {AIR, STONE}), 10, 0, 0,
                new int[0], new double[0], null, null, 0, null, null, AirflowRegionModel.DEFAULT_KAPPA, 4));
        assertEquals(4, four.region().mesh().sourceVelocityRadius(), "[heat] airflowSourceVelocityRadius");
        assertEquals(AdaptiveMesh3D.DEFAULT_SOURCE_VELOCITY_LINGER, four.region().mesh().sourceVelocityLinger());
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
    void aSavedRegionComesBackWarmAndDecays() {
        AirflowRegionModel warm = model(Map.of(cell(8, 1, 8), 20_000.0), true, null, 0);
        for (int i = 0; i < 40; i++) {
            warm.step();
        }
        double hot = warm.region().sampleT(7.5, 3.5, 7.5);
        assertTrue(hot > 11, "the source warms the closed room: " + hot);
        AirflowSaved.Tile tile = warm.save(500);
        assertEquals(1, tile.sections().length);
        assertTrue(tile.sections()[0].cells().length > 0, "something kept");
        assertTrue(java.util.Arrays.stream(tile.sections()[0].cells()).anyMatch(c -> (c & AirflowSaved.STORE) != 0),
                "the warmed floor's wall stores are kept too, one by one (M7f)");

        AirflowRegionModel back = model(Map.of(), true, tile.sections(), 500);
        assertTrue(back.restored());
        double again = back.region().sampleT(7.5, 3.5, 7.5);
        assertTrue(again > 10 + 0.5 * (hot - 10), "restored without the source, still warm: " + again + " (was " + hot + ")");
        AirflowRegionModel later = model(Map.of(), true, tile.sections(), 500 + 10 * (long) AirflowSaved.TAU_TICKS);
        assertEquals(10, later.region().sampleT(7.5, 3.5, 7.5), 0.01, "ten days later it is the background");
        assertFalse(model(Map.of()).restored());
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
