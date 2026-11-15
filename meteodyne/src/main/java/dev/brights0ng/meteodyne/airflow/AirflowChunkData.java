// Written by Claude (GreedyFluids M7d d2).
package dev.brights0ng.meteodyne.airflow;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * What airflow keeps about one chunk, saved with it (M7d d2): the latest {@link AirflowSaved.Section} of each of its
 * sections that a tile has saved, by section y. A section a tile saves with nothing kept is removed. Server thread
 * only. Version 1; data of an unknown version is dropped (it would only start that air at the background).
 */
public final class AirflowChunkData implements INBTSerializable<CompoundTag> {

    static final int VERSION = 1;

    private final Map<Integer, AirflowSaved.Section> sections = new HashMap<>();

    /** The saved section at section y {@code sy}, or null. */
    public AirflowSaved.Section section(int sy) {
        return sections.get(sy);
    }

    /** Replaces section {@code sy}'s values; an empty or null section removes them. */
    public void put(int sy, AirflowSaved.Section section) {
        if (section == null || section.isEmpty()) {
            sections.remove(sy);
        } else {
            sections.put(sy, section);
        }
    }

    public int size() {
        return sections.size();
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", VERSION);
        ListTag list = new ListTag();
        for (Map.Entry<Integer, AirflowSaved.Section> e : sections.entrySet()) {
            AirflowSaved.Section s = e.getValue();
            CompoundTag t = new CompoundTag();
            t.putInt("y", e.getKey());
            t.putLong("tick", s.tick());
            t.putIntArray("cells", s.cells());
            int[] bits = new int[s.offsets().length];
            for (int i = 0; i < bits.length; i++) {
                bits[i] = Float.floatToIntBits(s.offsets()[i]);
            }
            t.putIntArray("offsets", bits);
            list.add(t);
        }
        tag.put("sections", list);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        sections.clear();
        if (tag.getInt("version") != VERSION) {
            return;
        }
        ListTag list = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int[] cells = t.getIntArray("cells"), bits = t.getIntArray("offsets");
            if (cells.length != bits.length || !ascending(cells)) {
                continue;
            }
            float[] offsets = new float[bits.length];
            for (int k = 0; k < bits.length; k++) {
                offsets[k] = Float.intBitsToFloat(bits[k]);
            }
            put(t.getInt("y"), new AirflowSaved.Section(t.getLong("tick"), cells, offsets));
        }
    }

    private static boolean ascending(int[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] < 0 || cells[i] >= 2 * AirflowSaved.WALL || (i > 0 && cells[i] <= cells[i - 1])) {
                return false;
            }
        }
        return true;
    }
}
