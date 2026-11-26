// Written by Gemini.
package dev.brights0ng.empire.civilian;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.CivilianScheduleNbt;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.Occupation;
import dev.brights0ng.empire.rules.RegionRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Coordinates assigning, unassigning, pausing, and cleaning up citizen {@link Occupation}s against workplace
 * {@link RegionRecord}s.
 *
 * <p>Lifecycle rules (Bright, 2026-11-25):
 * <ul>
 *   <li>A {@code FARM} currently holds up to {@value RegionRules#FARM_MAX_WORKERS} worker.</li>
 *   <li>If a workplace becomes {@code INVALID}, workers stay assigned and pause work until it is repaired.</li>
 *   <li>If a workplace is deleted (last room removed, 3-day auto-expiry, debug remove, or empire disband), all
 *       assigned workers are detached and returned to {@link Occupation#UNEMPLOYED}.</li>
 * </ul>
 */
public final class CivilianOccupations {

    public enum AssignOutcome {
        OK,
        NOT_CITIZEN,
        WRONG_EMPIRE,
        UNSUPPORTED_REGION,
        REGION_INVALID,
        ALREADY_ASSIGNED_HERE,
        AT_CAPACITY
    }

    public record AssignResult(AssignOutcome outcome, @Nullable Occupation occupation,
                               @Nullable UUID previousWorkplaceId) {

        public boolean succeeded() {
            return outcome == AssignOutcome.OK;
        }
    }

    /** Returns the citizen's current {@link Occupation}, or {@link Occupation#UNEMPLOYED} if unassigned/debug-only. */
    public static Occupation occupationOf(CivilianEntity civilian) {
        WorkAssignment work = civilian.workAssignment();
        return work != null && work.isOccupation() ? work.occupation() : Occupation.UNEMPLOYED;
    }

    /** Returns the citizen's current {@link Occupation} from its master record. */
    public static Occupation occupationOf(CivilianRecord record) {
        WorkAssignment work = record.schedule().work();
        return work != null && work.isOccupation() ? work.occupation() : Occupation.UNEMPLOYED;
    }

    /** Returns the workplace region UUID assigned to {@code civilian}, or {@code null} if unemployed. */
    @Nullable
    public static UUID workplaceIdOf(CivilianEntity civilian) {
        WorkAssignment work = civilian.workAssignment();
        return work != null && work.isOccupation() ? work.workplaceRegionId() : null;
    }

    /** Counts live citizen records currently attached as workers of {@code region}. */
    public static int activeWorkerCount(EmpireSavedData data, RegionRecord region) {
        int count = 0;
        for (UUID civilianId : region.workers()) {
            if (data.civilian(civilianId) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Assigns {@code civilian} to work at {@code workplace}, detaching them from any previous workplace.
     */
    public static AssignResult assign(ServerLevel overworld, EmpireSavedData data,
                                      CivilianEntity civilian, RegionRecord workplace) {
        if (civilian.status() != CivilianEntity.Status.CITIZEN || civilian.civilianId() == null) {
            return new AssignResult(AssignOutcome.NOT_CITIZEN, null, null);
        }
        CivilianRecord record = civilian.ownRecord(data);
        if (record == null) {
            return new AssignResult(AssignOutcome.NOT_CITIZEN, null, null);
        }
        if (!record.empire().equals(workplace.empireId())) {
            return new AssignResult(AssignOutcome.WRONG_EMPIRE, null, null);
        }
        Occupation occupation = Occupation.forRegionType(workplace.type());
        if (occupation == null || !occupation.isEmployed()) {
            return new AssignResult(AssignOutcome.UNSUPPORTED_REGION, null, null);
        }
        if (!workplace.valid()) {
            return new AssignResult(AssignOutcome.REGION_INVALID, occupation, null);
        }

        WorkAssignment current = civilian.workAssignment();
        if (current != null && current.isOccupation() && workplace.id().equals(current.workplaceRegionId())) {
            if (workplace.addWorker(record.id())) {
                data.setDirty();
            }
            return new AssignResult(AssignOutcome.ALREADY_ASSIGNED_HERE, occupation, workplace.id());
        }

        pruneMissingWorkers(data, workplace);
        int cap = RegionRules.workerCapacity(workplace.type());
        if (workplace.workers().size() >= cap) {
            return new AssignResult(AssignOutcome.AT_CAPACITY, occupation, null);
        }

        UUID previousRegionId = null;
        if (current != null && current.workplaceRegionId() != null) {
            previousRegionId = current.workplaceRegionId();
            RegionRecord prev = data.region(previousRegionId);
            if (prev != null) {
                prev.removeWorker(record.id());
            }
        }

        workplace.addWorker(record.id());
        WorkAssignment assignment = WorkAssignment.forRegion(occupation, workplace.id(), BlockPos.of(workplace.seed()));
        civilian.setWorkAssignment(assignment);
        data.setDirty();
        return new AssignResult(AssignOutcome.OK, occupation, previousRegionId);
    }

    /**
     * Unassigns a loaded {@code civilian} from its current occupation/workplace, returning {@code true} if an
     * assignment was cleared.
     */
    public static boolean unassign(ServerLevel overworld, EmpireSavedData data, CivilianEntity civilian) {
        WorkAssignment current = civilian.workAssignment();
        if (current == null) {
            return false;
        }
        UUID civilianId = civilian.civilianId();
        if (civilianId != null && current.workplaceRegionId() != null) {
            RegionRecord prev = data.region(current.workplaceRegionId());
            if (prev != null) {
                prev.removeWorker(civilianId);
            }
        }
        civilian.setWorkAssignment(null);
        data.setDirty();
        return true;
    }

    /**
     * Unassigns a citizen by master record UUID (works whether its entity body is loaded or unloaded).
     * When {@code onlyIfRegionId} is non-null, clears the assignment only if it still points at that region.
     */
    public static boolean unassignRecord(ServerLevel overworld, EmpireSavedData data, UUID civilianId,
                                         @Nullable UUID onlyIfRegionId) {
        CivilianRecord record = data.civilian(civilianId);
        if (record == null) {
            return false;
        }
        CivilianScheduleNbt.SavedBundle bundle = record.schedule();
        WorkAssignment current = bundle.work();
        if (current == null) {
            return false;
        }
        if (onlyIfRegionId != null && !onlyIfRegionId.equals(current.workplaceRegionId())) {
            return false;
        }
        if (current.workplaceRegionId() != null) {
            RegionRecord prev = data.region(current.workplaceRegionId());
            if (prev != null) {
                prev.removeWorker(civilianId);
            }
        }
        record.setSchedule(new CivilianScheduleNbt.SavedBundle(
                bundle.schedule(), bundle.routes(), bundle.crowd(), null));
        if (overworld.getEntity(record.body()) instanceof CivilianEntity body) {
            body.setWorkAssignment(null);
        }
        data.setDirty();
        return true;
    }

    /**
     * Detaches all workers from a region that is being deleted (not merely invalidated), returning each to
     * {@link Occupation#UNEMPLOYED}.
     */
    public static void detachAllWorkers(ServerLevel overworld, EmpireSavedData data, RegionRecord region) {
        if (region.workers().isEmpty()) {
            return;
        }
        for (UUID civilianId : List.copyOf(region.workers())) {
            unassignRecord(overworld, data, civilianId, region.id());
        }
        region.clearWorkers();
        data.setDirty();
    }

    /**
     * Removes {@code civilianId} from its workplace region's worker set (called on death, re-recruitment, or debug clear).
     */
    public static void detachFromWorkplace(EmpireSavedData data, UUID civilianId, @Nullable WorkAssignment currentWork) {
        if (currentWork == null || currentWork.workplaceRegionId() == null) {
            return;
        }
        RegionRecord region = data.region(currentWork.workplaceRegionId());
        if (region != null && region.removeWorker(civilianId)) {
            data.setDirty();
        }
    }

    /**
     * Notifies loaded workers when their workplace region transitions {@code VALID <-> INVALID} without detaching
     * their assignment: invalidation pauses work immediately; restoration allows resuming work promptly.
     */
    public static void onWorkplaceValidityChanged(ServerLevel overworld, EmpireSavedData data, RegionRecord region) {
        if (region.workers().isEmpty()) {
            return;
        }
        for (UUID civilianId : List.copyOf(region.workers())) {
            CivilianRecord record = data.civilian(civilianId);
            if (record == null) {
                continue;
            }
            if (overworld.getEntity(record.body()) instanceof CivilianEntity body) {
                body.aiController().onWorkplaceStatusChanged(overworld);
            }
        }
    }

    private static void pruneMissingWorkers(EmpireSavedData data, RegionRecord workplace) {
        boolean changed = false;
        for (UUID id : List.copyOf(workplace.workers())) {
            if (data.civilian(id) == null) {
                workplace.removeWorker(id);
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
        }
    }

    private CivilianOccupations() {
    }
}
