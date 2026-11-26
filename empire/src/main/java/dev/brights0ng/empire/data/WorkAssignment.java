// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.CivilianSchedule;
import dev.brights0ng.empire.rules.Occupation;
import net.minecraft.core.BlockPos;

/**
 * Assigned workplace metadata for a citizen (set via the Administrative Ledger, debug command, or future Town Clerk).
 *
 * <p>Supports both real region-backed occupations ({@link #isOccupation()}, e.g. {@link Occupation#FARMER} bound to a
 * {@code FARM} {@link RegionRecord}) and coordinate-only debug work stubs ({@link #of(BlockPos, boolean, int, String)}).
 */
public record WorkAssignment(
        Occupation occupation,
        @Nullable UUID workplaceRegionId,
        @Nullable BlockPos anchor,
        boolean indoor,
        int dailyAskTicks,
        String label
) {

    public WorkAssignment {
        occupation = Objects.requireNonNull(occupation, "occupation");
        anchor = anchor != null ? anchor.immutable() : null;
        if (workplaceRegionId == null && anchor == null) {
            throw new IllegalArgumentException("WorkAssignment requires either workplaceRegionId or anchor");
        }
        dailyAskTicks = Math.clamp(dailyAskTicks, CivilianSchedule.MIN_WORK_TICKS, 16_800);
        label = (label == null || label.isBlank())
                ? (occupation.isEmployed() ? occupation.id() : "work")
                : label.trim();
    }

    /** Creates a coordinate-only debug work assignment (uses {@code StubWorkTask}). */
    public static WorkAssignment of(BlockPos anchor, boolean indoor, int dailyAskTicks, String label) {
        return new WorkAssignment(
                Occupation.UNEMPLOYED,
                null,
                Objects.requireNonNull(anchor, "anchor"),
                indoor,
                dailyAskTicks,
                label);
    }

    /** Creates a region-backed occupation assignment (e.g. {@link Occupation#FARMER} at a {@code FARM} region). */
    public static WorkAssignment forRegion(Occupation occupation, UUID workplaceRegionId, @Nullable BlockPos fallbackAnchor) {
        Objects.requireNonNull(occupation, "occupation");
        if (!occupation.isEmployed()) {
            throw new IllegalArgumentException("Region assignment requires an employed occupation");
        }
        return new WorkAssignment(
                occupation,
                Objects.requireNonNull(workplaceRegionId, "workplaceRegionId"),
                fallbackAnchor,
                occupation.indoor(),
                occupation.defaultDailyAskTicks(),
                occupation.id());
    }

    /** True if this assignment represents a real region-backed {@link Occupation}. */
    public boolean isOccupation() {
        return occupation.isEmployed() && workplaceRegionId != null;
    }
}
