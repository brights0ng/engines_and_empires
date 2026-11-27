// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import dev.brights0ng.empire.rules.CivilianSkill;
import dev.brights0ng.empire.rules.CivilianSkills;
import dev.brights0ng.empire.rules.CivilianSkills.Entry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Shared tolerant NBT codec for {@link CivilianSkills}, used by both {@link CivilianRecord} and
 * {@link dev.brights0ng.empire.civilian.CivilianEntity}.
 *
 * <p>Writes an explicit {@code "v": 1} marker alongside per-skill compounds so an intentionally cleared all-zero skill
 * set reloads as empty rather than triggering legacy migration. Missing or uninitialized compounds fall back to the
 * caller-supplied trait-aligned roll. Unknown skill keys are safely ignored for forward/backward compatibility.
 */
public final class CivilianSkillsNbt {

    private static final int FORMAT_VERSION = 1;
    private static final String KEY_VERSION = "v";
    private static final String KEY_XP = "xp";
    private static final String KEY_INACTIVE_DAYS = "inactive_days";
    private static final String KEY_USED_TODAY = "used_today";

    public static CompoundTag save(CivilianSkills.Saved saved) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_VERSION, FORMAT_VERSION);
        if (saved != null) {
            for (Map.Entry<CivilianSkill, Entry> e : saved.entries().entrySet()) {
                CivilianSkill skill = e.getKey();
                Entry entry = e.getValue();
                if (skill == null || entry == null || entry.isZero()) {
                    continue;
                }
                CompoundTag entryTag = new CompoundTag();
                entryTag.putDouble(KEY_XP, entry.xp());
                if (entry.inactiveDays() > 0) {
                    entryTag.putInt(KEY_INACTIVE_DAYS, entry.inactiveDays());
                }
                if (entry.usedToday()) {
                    entryTag.putBoolean(KEY_USED_TODAY, true);
                }
                tag.put(skill.id(), entryTag);
            }
        }
        return tag;
    }

    public static CivilianSkills.Saved load(CompoundTag tag, Supplier<CivilianSkills.Saved> fallback) {
        if (tag == null || tag.isEmpty() || !tag.contains(KEY_VERSION, Tag.TAG_ANY_NUMERIC)) {
            return fallback.get();
        }
        EnumMap<CivilianSkill, Entry> entries = new EnumMap<>(CivilianSkill.class);
        for (String key : tag.getAllKeys()) {
            if (KEY_VERSION.equals(key)) {
                continue;
            }
            CivilianSkill skill = CivilianSkill.byId(key);
            if (skill == null || !tag.contains(key, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag entryTag = tag.getCompound(key);
            double xp = entryTag.contains(KEY_XP, Tag.TAG_ANY_NUMERIC) ? entryTag.getDouble(KEY_XP) : 0.0;
            int inactive = entryTag.contains(KEY_INACTIVE_DAYS, Tag.TAG_ANY_NUMERIC)
                    ? entryTag.getInt(KEY_INACTIVE_DAYS) : 0;
            boolean used = entryTag.getBoolean(KEY_USED_TODAY);
            Entry entry = new Entry(xp, inactive, used);
            if (!entry.isZero()) {
                entries.put(skill, entry);
            }
        }
        return new CivilianSkills.Saved(entries);
    }

    private CivilianSkillsNbt() {
    }
}
