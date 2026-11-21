// Written by Gemini.
package dev.brights0ng.empire.data;

import dev.brights0ng.empire.rules.CivilianSleep;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Shared, tolerant persistence for a body's sleep state and its record's immutable snapshot. */
public final class CivilianSleepNbt {

    public static CompoundTag save(CivilianSleep.Saved sleep) {
        CompoundTag tag = new CompoundTag();
        if (sleep.sessionNight() != CivilianSleep.NO_NIGHT) {
            tag.putLong("session_night", sleep.sessionNight());
        }
        if (sleep.restedNight() != CivilianSleep.NO_NIGHT) {
            tag.putLong("rested_night", sleep.restedNight());
        }
        tag.putInt("slept_ticks", sleep.sleptTicks());
        tag.putFloat("credit_ticks", sleep.creditTicks());
        if (!Float.isNaN(sleep.lastNight())) {
            tag.putFloat("last_night", sleep.lastNight());
        }
        if (sleep.lastDayTime() != CivilianSleep.NO_DAY_TIME) {
            tag.putLong("last_day_time", sleep.lastDayTime());
        }
        return tag;
    }

    public static CivilianSleep.Saved load(CompoundTag tag) {
        long sessionNight = tag.contains("session_night", Tag.TAG_ANY_NUMERIC)
                ? tag.getLong("session_night") : CivilianSleep.NO_NIGHT;
        long restedNight = tag.contains("rested_night", Tag.TAG_ANY_NUMERIC)
                ? tag.getLong("rested_night") : CivilianSleep.NO_NIGHT;
        int sleptTicks = tag.contains("slept_ticks", Tag.TAG_ANY_NUMERIC)
                ? Math.max(0, tag.getInt("slept_ticks")) : 0;
        float rawCredit = tag.contains("credit_ticks", Tag.TAG_ANY_NUMERIC) ? tag.getFloat("credit_ticks") : 0.0F;
        float creditTicks = Float.isFinite(rawCredit) ? Math.max(0.0F, rawCredit) : 0.0F;
        float lastNight = Float.NaN;
        if (tag.contains("last_night", Tag.TAG_ANY_NUMERIC)) {
            float rawLast = tag.getFloat("last_night");
            if (Float.isFinite(rawLast)) {
                lastNight = Math.clamp(rawLast, 0.0F, 1.0F);
            }
        }
        long lastDayTime = tag.contains("last_day_time", Tag.TAG_ANY_NUMERIC)
                ? tag.getLong("last_day_time") : CivilianSleep.NO_DAY_TIME;
        return new CivilianSleep.Saved(sessionNight, restedNight, sleptTicks, creditTicks, lastNight, lastDayTime);
    }

    private CivilianSleepNbt() {
    }
}
