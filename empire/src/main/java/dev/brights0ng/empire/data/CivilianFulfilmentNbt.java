// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import dev.brights0ng.empire.rules.CivilianFulfilment;
import dev.brights0ng.empire.rules.CivilianPreferences;
import dev.brights0ng.empire.rules.FulfilmentModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Shared tolerant NBT codec for {@link CivilianFulfilment.Saved}, used by both {@link CivilianRecord} and
 * {@link dev.brights0ng.empire.civilian.CivilianEntity}.
 */
public final class CivilianFulfilmentNbt {

    private static final String KEY_FAVOURITE_FOODS = "favourite_foods";
    private static final String KEY_FAVOURITE_ACTIVITIES = "favourite_activities";
    private static final String KEY_CITIZEN_ACTIVE_TICKS = "citizen_active_ticks";
    private static final String KEY_COMMUNITY_DAYS = "community_days";
    private static final String KEY_SOCIALIZING = "socializing_days";
    private static final String KEY_SOCIAL_COOLDOWNS = "social_cooldowns";
    private static final String KEY_LIKED_MEALS = "liked_meals_days";
    private static final String KEY_LIKED_HOBBY_TICKS = "liked_hobby_ticks_days";
    private static final String KEY_COMFORT_SAMPLES = "comfort_samples";
    private static final String KEY_GRIEVANCE = "grievance";
    private static final String KEY_TICKS_SINCE_SLOT = "ticks_since_slot";

    public static CompoundTag save(CivilianFulfilment.Saved state) {
        CompoundTag tag = new CompoundTag();

        ListTag foodsTag = new ListTag();
        for (String id : state.preferences().favouriteFoods()) {
            foodsTag.add(StringTag.valueOf(id));
        }
        tag.put(KEY_FAVOURITE_FOODS, foodsTag);

        ListTag actsTag = new ListTag();
        for (CivilianPreferences.Activity act : state.preferences().favouriteActivities()) {
            actsTag.add(StringTag.valueOf(act.id()));
        }
        tag.put(KEY_FAVOURITE_ACTIVITIES, actsTag);

        tag.putLong(KEY_CITIZEN_ACTIVE_TICKS, state.citizenActiveTicks());

        ListTag commDaysTag = new ListTag();
        for (int i = 0; i < CivilianFulfilment.WINDOW_DAYS; i++) {
            ListTag dayTag = new ListTag();
            if (i < state.communityDays().size()) {
                for (UUID uuid : state.communityDays().get(i)) {
                    dayTag.add(NbtUtils.createUUID(uuid));
                }
            }
            commDaysTag.add(dayTag);
        }
        tag.put(KEY_COMMUNITY_DAYS, commDaysTag);

        tag.putIntArray(KEY_SOCIALIZING, new int[] {
                state.socializingDay0(), state.socializingDay1(), state.socializingDay2()
        });

        ListTag cooldownsTag = new ListTag();
        for (Map.Entry<UUID, Long> entry : state.lastSocialTickByPartner().entrySet()) {
            CompoundTag ct = new CompoundTag();
            ct.putUUID("partner", entry.getKey());
            ct.putLong("tick", entry.getValue());
            cooldownsTag.add(ct);
        }
        tag.put(KEY_SOCIAL_COOLDOWNS, cooldownsTag);

        tag.putIntArray(KEY_LIKED_MEALS, new int[] {
                state.likedMealsDay0(), state.likedMealsDay1(), state.likedMealsDay2()
        });
        tag.putLongArray(KEY_LIKED_HOBBY_TICKS, new long[] {
                state.likedHobbyTicksDay0(), state.likedHobbyTicksDay1(), state.likedHobbyTicksDay2()
        });

        ListTag comfortTag = new ListTag();
        for (float sample : state.comfortSamples()) {
            if (Float.isFinite(sample)) {
                comfortTag.add(FloatTag.valueOf(sample));
            }
        }
        tag.put(KEY_COMFORT_SAMPLES, comfortTag);

        tag.putFloat(KEY_GRIEVANCE, state.grievance());
        tag.putLong(KEY_TICKS_SINCE_SLOT, state.ticksSinceLastSlot());
        return tag;
    }

    /**
     * Loads a saved fulfilment snapshot with independent safe defaults for every field. Missing or empty preferences
     * fall back to {@code defaultPreferences}.
     */
    public static CivilianFulfilment.Saved load(CompoundTag tag, Supplier<CivilianPreferences> defaultPreferences) {
        CivilianPreferences fallback = null;

        List<String> foods = new ArrayList<>();
        if (tag.contains(KEY_FAVOURITE_FOODS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_FAVOURITE_FOODS, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String id = list.getString(i);
                if (!id.isBlank()) {
                    foods.add(id);
                }
            }
        }
        List<CivilianPreferences.Activity> activities = new ArrayList<>();
        if (tag.contains(KEY_FAVOURITE_ACTIVITIES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_FAVOURITE_ACTIVITIES, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String rawId = list.getString(i);
                CivilianPreferences.Activity act = "pub_chat".equalsIgnoreCase(rawId)
                        ? CivilianPreferences.Activity.READING
                        : CivilianPreferences.Activity.byId(rawId);
                if (act != null) {
                    activities.add(act);
                }
            }
        }
        if (foods.isEmpty() || activities.isEmpty()) {
            fallback = defaultPreferences.get();
            if (foods.isEmpty()) {
                foods = fallback.favouriteFoods();
            }
            if (activities.isEmpty()) {
                activities = fallback.favouriteActivities();
            }
        }
        CivilianPreferences prefs = new CivilianPreferences(foods, activities);

        long citizenTicks = tag.contains(KEY_CITIZEN_ACTIVE_TICKS, Tag.TAG_ANY_NUMERIC)
                ? Math.max(0L, tag.getLong(KEY_CITIZEN_ACTIVE_TICKS))
                : 0L;

        List<Set<UUID>> communityDays = new ArrayList<>(CivilianFulfilment.WINDOW_DAYS);
        if (tag.contains(KEY_COMMUNITY_DAYS, Tag.TAG_LIST)) {
            ListTag outer = tag.getList(KEY_COMMUNITY_DAYS, Tag.TAG_LIST);
            for (int i = 0; i < CivilianFulfilment.WINDOW_DAYS; i++) {
                Set<UUID> daySet = new HashSet<>();
                if (i < outer.size()) {
                    ListTag inner = outer.getList(i);
                    for (int j = 0; j < inner.size(); j++) {
                        Tag elem = inner.get(j);
                        if (elem.getId() == Tag.TAG_INT_ARRAY) {
                            try {
                                daySet.add(NbtUtils.loadUUID(elem));
                            } catch (IllegalArgumentException ignored) {
                                // Ignore corrupt UUID entries.
                            }
                        }
                    }
                }
                communityDays.add(Collections.unmodifiableSet(daySet));
            }
        } else {
            for (int i = 0; i < CivilianFulfilment.WINDOW_DAYS; i++) {
                communityDays.add(Set.of());
            }
        }

        int[] soc = tag.contains(KEY_SOCIALIZING, Tag.TAG_INT_ARRAY) ? tag.getIntArray(KEY_SOCIALIZING) : new int[0];
        int soc0 = soc.length > 0 ? Math.max(0, soc[0]) : 0;
        int soc1 = soc.length > 1 ? Math.max(0, soc[1]) : 0;
        int soc2 = soc.length > 2 ? Math.max(0, soc[2]) : 0;

        Map<UUID, Long> cooldowns = new LinkedHashMap<>();
        if (tag.contains(KEY_SOCIAL_COOLDOWNS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_SOCIAL_COOLDOWNS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag ct = list.getCompound(i);
                if (ct.hasUUID("partner") && ct.contains("tick", Tag.TAG_ANY_NUMERIC)) {
                    cooldowns.put(ct.getUUID("partner"), Math.max(0L, ct.getLong("tick")));
                }
            }
        }

        int[] meals = tag.contains(KEY_LIKED_MEALS, Tag.TAG_INT_ARRAY) ? tag.getIntArray(KEY_LIKED_MEALS) : new int[0];
        int meal0 = meals.length > 0 ? Math.max(0, meals[0]) : 0;
        int meal1 = meals.length > 1 ? Math.max(0, meals[1]) : 0;
        int meal2 = meals.length > 2 ? Math.max(0, meals[2]) : 0;

        long[] hobbyTicks = tag.contains(KEY_LIKED_HOBBY_TICKS, Tag.TAG_LONG_ARRAY)
                ? tag.getLongArray(KEY_LIKED_HOBBY_TICKS) : new long[0];
        long hobby0 = hobbyTicks.length > 0 ? Math.max(0L, hobbyTicks[0]) : 0L;
        long hobby1 = hobbyTicks.length > 1 ? Math.max(0L, hobbyTicks[1]) : 0L;
        long hobby2 = hobbyTicks.length > 2 ? Math.max(0L, hobbyTicks[2]) : 0L;

        List<Float> comfortSamples = new ArrayList<>();
        if (tag.contains(KEY_COMFORT_SAMPLES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_COMFORT_SAMPLES, Tag.TAG_FLOAT);
            int start = Math.max(0, list.size() - CivilianFulfilment.COMFORT_DAY_SLOTS);
            for (int i = start; i < list.size(); i++) {
                float val = list.getFloat(i);
                if (Float.isFinite(val)) {
                    comfortSamples.add((float) FulfilmentModel.clamp01(val));
                }
            }
        }

        float grievance = tag.contains(KEY_GRIEVANCE, Tag.TAG_ANY_NUMERIC)
                ? (float) FulfilmentModel.clamp01(tag.getFloat(KEY_GRIEVANCE))
                : 0.0F;
        long ticksSinceSlot = tag.contains(KEY_TICKS_SINCE_SLOT, Tag.TAG_ANY_NUMERIC)
                ? Math.max(0L, tag.getLong(KEY_TICKS_SINCE_SLOT))
                : 0L;

        return new CivilianFulfilment.Saved(
                prefs,
                citizenTicks,
                Collections.unmodifiableList(communityDays),
                soc0, soc1, soc2,
                Collections.unmodifiableMap(cooldowns),
                meal0, meal1, meal2,
                hobby0, hobby1, hobby2,
                List.copyOf(comfortSamples),
                grievance,
                ticksSinceSlot
        );
    }

    private CivilianFulfilmentNbt() {
    }
}
