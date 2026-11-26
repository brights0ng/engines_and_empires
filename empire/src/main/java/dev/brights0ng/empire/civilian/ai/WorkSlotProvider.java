// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.List;

import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.Occupation;

/**
 * Extensible Level-2 workplace provider interface: given a citizen's {@link WorkAssignment}, supplies the candidate
 * Level-3 {@link CivilianTask}s for a {@code WORK} Slot. Each {@link Occupation} registers its tasks here without
 * changing the scheduler or slot runner.
 */
@FunctionalInterface
public interface WorkSlotProvider {

    List<CivilianTask> createWorkTasks(WorkAssignment assignment, LocationResolver locations);

    /** Occupation-dispatched provider with fallback to {@link StubWorkTask} for coordinate-only debug assignments. */
    WorkSlotProvider DEFAULT = (assignment, locations) -> {
        if (assignment.isOccupation()) {
            return switch (assignment.occupation()) {
                case FARMER -> {
                    FarmWorkScanner scanner = new FarmWorkScanner();
                    yield List.of(
                            new EndShiftStoreWorkGearTask(scanner),
                            new DumpToStorageTask(scanner),
                            new FetchSuppliesTask(scanner),
                            new RetillSoilTask(scanner),
                            new HarvestAndReplantTask(scanner),
                            new PlantSeedsTask(scanner),
                            new PickupDropsTask(scanner),
                            new FarmerStandByShedTask(locations)
                    );
                }
                case UNEMPLOYED -> List.of();
            };
        }
        return assignment.anchor() != null ? List.of(new StubWorkTask(assignment)) : List.of();
    };
}
