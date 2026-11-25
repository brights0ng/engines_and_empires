// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.List;

import dev.brights0ng.empire.data.WorkAssignment;

/**
 * Extensible Level-2 workplace provider interface: given a citizen's {@link WorkAssignment}, supplies the candidate
 * Level-3 {@link CivilianTask}s for a {@code WORK} Slot. Future workplace regions ({@code FARM}, {@code WORKSHOP},
 * etc.) implement this interface without changing the scheduler or slot runner.
 */
@FunctionalInterface
public interface WorkSlotProvider {

    List<CivilianTask> createWorkTasks(WorkAssignment assignment);

    /** Default placeholder provider until concrete workplace regions exist. */
    WorkSlotProvider DEFAULT = assignment -> List.of(new StubWorkTask(assignment));
}
