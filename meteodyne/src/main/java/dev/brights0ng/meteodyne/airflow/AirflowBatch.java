// Written by Claude (GreedyFluids M7b b3); edited by Claude (M7c: fixed walls).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.greedyfluids.grid.CellEdit3D;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;

/**
 * The cell changes queued for one region between two steps (M7b b3), merged into GreedyFluids edits. Changes to one
 * cell merge into one, from its first "before" to its last "after"; a net no-op is dropped, and so is a change of
 * material alone (SOLID to SOLID, or a door to a door in the same state: ignored in M7).
 * <ul>
 *   <li>AIR↔SOLID and AIR↔DOOR are one edit, in {@link #first}.</li>
 *   <li>SOLID↔DOOR is two: GreedyFluids takes one edit per cell per batch, so the second (the new door or block) goes
 *       in {@link #second}, applied after {@code first}.</li>
 *   <li>A door opening or shutting is a {@link #doors} toggle, not an edit.</li>
 *   <li>A solid whose fixed wall temperature changes (lava placed, lava turned to obsidian; M7c) is a
 *       {@link #walls} entry, applied after both edit lists; a material change alone still makes no edit.</li>
 * </ul>
 * Pure Java: no Minecraft classes, so the unit tests can run it.
 */
final class AirflowBatch {

    /** One cell's change, region cells. */
    record Change(int x, int y, int z, Cell before, Cell after) {
    }

    /** A door cell opening or shutting. */
    record DoorToggle(int x, int y, int z, boolean open) {
    }

    /** A solid cell's fixed wall temperature, C (NaN: none). */
    record WallChange(int x, int y, int z, double celsius) {
    }

    final List<CellEdit3D> first = new ArrayList<>();
    final List<CellEdit3D> second = new ArrayList<>();
    final List<DoorToggle> doors = new ArrayList<>();
    final List<WallChange> walls = new ArrayList<>();
    /** Cells whose changes came to nothing (or to a material change alone). */
    int dropped;

    /** Merges {@code changes}, in the order they happened, for a region {@code nx} by {@code nz} cells across. */
    static AirflowBatch of(List<Change> changes, int nx, int nz) {
        Map<Integer, Change[]> net = new LinkedHashMap<>();
        for (Change c : changes) {
            Change[] firstLast = net.computeIfAbsent((c.y() * nz + c.z()) * nx + c.x(), k -> new Change[] {c, c});
            firstLast[1] = c;
        }
        AirflowBatch batch = new AirflowBatch();
        for (Change[] firstLast : net.values()) {
            batch.add(firstLast[0].x(), firstLast[0].y(), firstLast[0].z(), firstLast[0].before(), firstLast[1].after());
        }
        return batch;
    }

    private void add(int x, int y, int z, Cell before, Cell after) {
        Kind b = before.kind(), a = after.kind();
        if (a == Kind.SOLID && (b != Kind.SOLID ? !Double.isNaN(after.wall())
                : Double.compare(before.wall(), after.wall()) != 0)) {
            walls.add(new WallChange(x, y, z, after.wall()));
        }
        if (b == a) {
            dropped++;
            return;
        }
        boolean doorBefore = door(b), doorAfter = door(a);
        if (doorBefore && doorAfter) {
            doors.add(new DoorToggle(x, y, z, a == Kind.DOOR_OPEN));
            return;
        }
        // Out of the old cell: AIR stays, SOLID breaks, a door is removed
        if (b == Kind.SOLID) {
            first.add(CellEdit3D.breakBlock(x, y, z));
        } else if (doorBefore) {
            first.add(CellEdit3D.removeDoor(x, y, z));
        }
        // Into the new one, in the same batch from AIR, else in the second
        List<CellEdit3D> into = b == Kind.AIR ? first : second;
        if (a == Kind.SOLID) {
            into.add(CellEdit3D.place(x, y, z, after.material()));
        } else if (doorAfter) {
            into.add(CellEdit3D.placeDoor(x, y, z, after.material(), a == Kind.DOOR_OPEN));
        }
    }

    private static boolean door(Kind k) {
        return k == Kind.DOOR_SHUT || k == Kind.DOOR_OPEN;
    }

    boolean isEmpty() {
        return first.isEmpty() && second.isEmpty() && doors.isEmpty() && walls.isEmpty();
    }
}
