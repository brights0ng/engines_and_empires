// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;

public final class BonfireRegistry {

    private final Set<BlockPos> bonfires = new HashSet<>();

    public void add(BlockPos pos) {
        bonfires.add(pos.immutable());
    }

    public void remove(BlockPos pos) {
        bonfires.remove(pos);
    }

    public boolean contains(BlockPos pos) {
        return bonfires.contains(pos);
    }

    public Set<BlockPos> all() {
        return Collections.unmodifiableSet(bonfires);
    }

    public int size() {
        return bonfires.size();
    }

    public void clear() {
        bonfires.clear();
    }

    public void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (BlockPos pos : bonfires) {
            list.add(LongTag.valueOf(pos.asLong()));
        }
        tag.put("positions", list);
    }

    public void load(CompoundTag tag) {
        bonfires.clear();
        ListTag list = tag.getList("positions", Tag.TAG_LONG);
        for (int i = 0; i < list.size(); i++) {
            bonfires.add(BlockPos.of(((LongTag) list.get(i)).getAsLong()));
        }
    }
}
