// Written by Claude (GreedyFluids M7b b3).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.brights0ng.greedyfluids.grid.CellEdit3D;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.meteodyne.airflow.AirflowBatch.Change;
import dev.brights0ng.meteodyne.airflow.AirflowBatch.DoorToggle;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;
import org.junit.jupiter.api.Test;

/** GreedyFluids M7b b3: per-cell merging of queued edits into GreedyFluids batches. */
class AirflowBatchTest {

    static final Material WOOD = new Material("wood", 0.035 * 0.25, 3.5 * 0.5);
    static final Cell AIR = new Cell(Kind.AIR, null);
    static final Cell STONE = new Cell(Kind.SOLID, Material.STONE);
    static final Cell PLANKS = new Cell(Kind.SOLID, WOOD);
    static final Cell SHUT = new Cell(Kind.DOOR_SHUT, WOOD);
    static final Cell OPEN = new Cell(Kind.DOOR_OPEN, WOOD);

    private static AirflowBatch batch(Change... changes) {
        return AirflowBatch.of(List.of(changes), 16, 16);
    }

    @Test
    void oneEditPerCellFromItsFirstBeforeToItsLastAfter() {
        AirflowBatch b = batch(new Change(1, 2, 3, AIR, STONE), new Change(1, 2, 3, STONE, AIR),
                new Change(1, 2, 3, AIR, PLANKS), new Change(4, 4, 4, STONE, AIR));
        assertEquals(List.of(CellEdit3D.place(1, 2, 3, WOOD), CellEdit3D.breakBlock(4, 4, 4)), b.first,
                "first seen order, net change per cell");
        assertTrue(b.second.isEmpty());
        assertEquals(0, b.dropped);
    }

    @Test
    void netNoOpsAndMaterialChangesAreDropped() {
        AirflowBatch b = batch(new Change(0, 0, 0, AIR, STONE), new Change(0, 0, 0, STONE, AIR),
                new Change(1, 0, 0, STONE, PLANKS), new Change(2, 0, 0, SHUT, new Cell(Kind.DOOR_SHUT, Material.DOOR)));
        assertTrue(b.isEmpty());
        assertEquals(3, b.dropped);
    }

    @Test
    void doorsArePlacedRemovedAndToggled() {
        AirflowBatch b = batch(new Change(0, 1, 0, AIR, OPEN), new Change(0, 2, 0, SHUT, AIR),
                new Change(0, 3, 0, SHUT, OPEN), new Change(0, 4, 0, OPEN, SHUT), new Change(0, 4, 0, SHUT, OPEN));
        assertEquals(List.of(CellEdit3D.placeDoor(0, 1, 0, WOOD, true), CellEdit3D.removeDoor(0, 2, 0)), b.first);
        assertEquals(List.of(new DoorToggle(0, 3, 0, true)), b.doors);
        assertEquals(1, b.dropped, "shut, then open again, is no change");
    }

    @Test
    void solidToDoorAndBackTakeTwoBatches() {
        AirflowBatch b = batch(new Change(5, 5, 5, STONE, SHUT), new Change(6, 5, 5, OPEN, PLANKS));
        assertEquals(List.of(CellEdit3D.breakBlock(5, 5, 5), CellEdit3D.removeDoor(6, 5, 5)), b.first);
        assertEquals(List.of(CellEdit3D.placeDoor(5, 5, 5, WOOD, false), CellEdit3D.place(6, 5, 5, WOOD)), b.second);
    }
}
