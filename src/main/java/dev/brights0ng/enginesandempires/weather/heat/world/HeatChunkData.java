package dev.brights0ng.enginesandempires.weather.heat.world;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * What the heat simulation keeps about one chunk, saved with it (weather phase 8f-2, decision 9: a room comes back as
 * it was): the boxes of the saved regions that touch it, and the offsets of its cells in them (only those not near 0).
 * Regions made of natural sources alone are never saved. Server thread only.
 */
public final class HeatChunkData implements INBTSerializable<CompoundTag> {

    /** Offsets closer to 0 than this are not kept, C. */
    static final float KEEP = 0.02f;

    /** Region boxes, {minX, minY, minZ, maxX, maxY, maxZ} inclusive, world blocks. */
    private final List<int[]> boxes = new ArrayList<>();
    private final Long2FloatOpenHashMap values = new Long2FloatOpenHashMap();

    public boolean isEmpty() {
        return boxes.isEmpty() && values.isEmpty();
    }

    public List<int[]> boxes() {
        return boxes;
    }

    /** The saved offset at {@code pos} ({@code BlockPos.asLong}), or NaN. */
    public float value(long pos) {
        return values.containsKey(pos) ? values.get(pos) : Float.NaN;
    }

    /**
     * Forgets every box overlapping {@code box} (an older region's, replaced) and the values inside {@code box} and
     * inside those boxes.
     */
    public void clearBox(int[] box) {
        List<int[]> gone = new ArrayList<>();
        gone.add(box);
        boxes.removeIf(b -> {
            if (overlaps(b, box)) {
                gone.add(b);
                return true;
            }
            return false;
        });
        values.long2FloatEntrySet().removeIf(e -> {
            for (int[] g : gone) {
                if (inside(g, e.getLongKey())) {
                    return true;
                }
            }
            return false;
        });
    }

    public void addBox(int[] box) {
        boxes.add(box.clone());
    }

    public void put(long pos, float offset) {
        if (Math.abs(offset) >= KEEP) {
            values.put(pos, offset);
        }
    }

    static boolean inside(int[] b, long pos) {
        int x = BlockPos.getX(pos);
        int y = BlockPos.getY(pos);
        int z = BlockPos.getZ(pos);
        return x >= b[0] && y >= b[1] && z >= b[2] && x <= b[3] && y <= b[4] && z <= b[5];
    }

    static boolean overlaps(int[] a, int[] b) {
        return a[0] <= b[3] && b[0] <= a[3] && a[1] <= b[4] && b[1] <= a[4] && a[2] <= b[5] && b[2] <= a[5];
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (int[] b : boxes) {
            list.add(new IntArrayTag(b));
        }
        tag.put("boxes", list);
        long[] positions = new long[values.size()];
        int[] offsets = new int[values.size()];
        int i = 0;
        for (Long2FloatMap.Entry e : values.long2FloatEntrySet()) {
            positions[i] = e.getLongKey();
            offsets[i] = Float.floatToIntBits(e.getFloatValue());
            i++;
        }
        tag.putLongArray("pos", positions);
        tag.putIntArray("offset", offsets);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        boxes.clear();
        values.clear();
        ListTag list = tag.getList("boxes", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            int[] b = list.getIntArray(i);
            if (b.length == 6) {
                boxes.add(b);
            }
        }
        long[] positions = tag.getLongArray("pos");
        int[] offsets = tag.getIntArray("offset");
        for (int i = 0; i < positions.length && i < offsets.length; i++) {
            values.put(positions[i], Float.intBitsToFloat(offsets[i]));
        }
    }
}
