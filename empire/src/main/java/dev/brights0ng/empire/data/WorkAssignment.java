// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.Objects;

import dev.brights0ng.empire.rules.CivilianSchedule;
import net.minecraft.core.BlockPos;

/**
 * Assigned workplace metadata for a citizen (set by a player, debug command, or future Town Clerk).
 *
 * <p>Kept minimal and extensible so upcoming workplace regions ({@code FARM}, {@code WORKSHOP}, etc.) can bind their
 * own job providers without changing persistence.
 */
public record WorkAssignment(BlockPos anchor, boolean indoor, int dailyAskTicks, String label) {

    public WorkAssignment {
        anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        dailyAskTicks = Math.clamp(dailyAskTicks, CivilianSchedule.MIN_WORK_TICKS, 16_800);
        label = (label == null || label.isBlank()) ? "work" : label.trim();
    }

    public static WorkAssignment of(BlockPos anchor, boolean indoor, int dailyAskTicks, String label) {
        return new WorkAssignment(anchor, indoor, dailyAskTicks, label);
    }
}
