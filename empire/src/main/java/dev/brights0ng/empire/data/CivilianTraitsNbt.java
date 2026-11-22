// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.function.Supplier;

import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Shared tolerant NBT codec for {@link CivilianTraits}, used by both {@link CivilianRecord} and
 * {@link dev.brights0ng.empire.civilian.CivilianEntity}.
 *
 * <p>Stores all 11 base axes by their lowercase {@link BaseTrait#id()}. Missing, empty, wrong-type, or out-of-range
 * compounds fall back to the caller-supplied default (UUID-seeded for legacy records, fresh roll for entities).
 */
public final class CivilianTraitsNbt {

    public static CompoundTag save(CivilianTraits traits) {
        CompoundTag tag = new CompoundTag();
        for (BaseTrait trait : BaseTrait.values()) {
            tag.putInt(trait.id(), traits.raw(trait));
        }
        return tag;
    }

    public static CivilianTraits load(CompoundTag tag, Supplier<CivilianTraits> fallback) {
        if (tag == null || tag.isEmpty()) {
            return fallback.get();
        }
        int[] values = new int[CivilianTraits.TRAIT_COUNT];
        for (BaseTrait trait : BaseTrait.values()) {
            if (!tag.contains(trait.id(), Tag.TAG_ANY_NUMERIC)) {
                return fallback.get();
            }
            int v = tag.getInt(trait.id());
            if (v < CivilianTraits.MIN_VALUE || v > CivilianTraits.MAX_VALUE) {
                return fallback.get();
            }
            values[trait.ordinal()] = v;
        }
        return CivilianTraits.of(values);
    }

    private CivilianTraitsNbt() {
    }
}
