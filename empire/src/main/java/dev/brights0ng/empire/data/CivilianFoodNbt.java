// Written by ChatGPT.
package dev.brights0ng.empire.data;

import dev.brights0ng.empire.rules.CivilianFood;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Shared, tolerant persistence for a body's food and its record's immutable snapshot. */
public final class CivilianFoodNbt {
    public static CompoundTag save(CivilianFood.Saved food) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("food_level", food.foodLevel());
        tag.putFloat("saturation", food.saturation());
        tag.putFloat("exhaustion", food.exhaustion());
        tag.putInt("tick_timer", food.tickTimer());
        tag.putInt("eaten_today", food.foodEatenToday());
        tag.putInt("eaten_yesterday", food.foodEatenYesterday());
        tag.putLong("active_ticks", food.activeTicks());
        return tag;
    }

    public static CivilianFood.Saved load(CompoundTag tag) {
        int level = tag.contains("food_level", Tag.TAG_ANY_NUMERIC) ? Math.clamp(tag.getInt("food_level"), 0, 20) : 20;
        float saturation = number(tag, "saturation", 5.0F, level);
        float exhaustion = number(tag, "exhaustion", 0.0F, 40.0F);
        return new CivilianFood.Saved(level, saturation, exhaustion,
                Math.clamp(tag.getInt("tick_timer"), 0, 80), Math.max(0, tag.getInt("eaten_today")),
                Math.max(0, tag.getInt("eaten_yesterday")), Math.max(0L, tag.getLong("active_ticks")));
    }

    private static float number(CompoundTag tag, String key, float fallback, float max) {
        float value = tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getFloat(key) : fallback;
        return Math.clamp(Float.isFinite(value) ? value : fallback, 0.0F, max);
    }

    private CivilianFoodNbt() {
    }
}
