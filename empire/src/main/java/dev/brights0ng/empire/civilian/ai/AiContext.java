// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ScheduleClock;
import net.minecraft.server.level.ServerLevel;

/** Per-tick world context shared across Level 1–4 Civilian AI components. */
public record AiContext(
        ServerLevel level,
        CivilianEntity civilian,
        EmpireSavedData data,
        @Nullable EmpireRecord empire,
        long dayTime,
        long scheduleDay,
        int tickOfDay
) {
    public static AiContext of(ServerLevel level, CivilianEntity civilian, EmpireSavedData data,
                               @Nullable EmpireRecord empire) {
        long dayTime = level.getDayTime();
        return new AiContext(
                level,
                civilian,
                data,
                empire,
                dayTime,
                ScheduleClock.scheduleDay(dayTime),
                ScheduleClock.timeOfDay(dayTime)
        );
    }
}
