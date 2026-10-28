// Updated by ChatGPT: versioned independent local-air thermal cells alongside unchanged legacy offsets.
package dev.brights0ng.meteodyne.heat.world;

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
    private final java.util.Map<Long,LocalAirThermalState.Saved> air = new java.util.HashMap<>();
    private CompoundTag unknownAir;
    private static final int AIR_VERSION=1,MAX_AIR_CELLS=131072;
    LocalAirThermalState.Saved airValue(long pos) {
        if(unknownAir!=null) throw new IllegalStateException("Unsupported or invalid local-air save data; preserved without migration");
        return air.get(pos);
    }
    void putAir(long pos,LocalAirThermalState.Saved saved) {
        if(unknownAir!=null) throw new IllegalStateException("Cannot overwrite unknown local-air save version");
        if(!air.containsKey(pos) && air.size()>=MAX_AIR_CELLS) throw new IllegalStateException("Local-air chunk storage limit exceeded");
        var old=air.get(pos);if(old==null || saved.tick>=old.tick) air.put(pos,saved);
    }
    void clearAir(int[] box) { if(unknownAir!=null) throw new IllegalStateException("Unknown local-air save version");air.keySet().removeIf(pos->inside(box,pos)); }
    int airCells() { return air.size(); }
    private CompoundTag serializeAir() {
        if(unknownAir!=null) return unknownAir.copy();
        var tag=new CompoundTag();tag.putInt("version",AIR_VERSION);var list=new ListTag();
        for(var entry:air.entrySet()) {
            var cell=new CompoundTag();var saved=entry.getValue();var m=saved.material;
            cell.putLong("pos",entry.getKey());cell.putLong("tick",saved.tick);
            cell.putIntArray("solid",m.shape().solidBoxes());cell.putIntArray("fluid",m.shape().fluidBoxes());
            double[] material={m.solidK(),m.solidCapacity(),m.fluidK(),m.fluidCapacity()};
            cell.putLongArray("material",java.util.Arrays.stream(material).mapToLong(Double::doubleToLongBits).toArray());
            cell.putLongArray("offsets",java.util.Arrays.stream(saved.offsets()).mapToLong(Double::doubleToLongBits).toArray());list.add(cell);
        }
        tag.put("cells",list);return tag;
    }
    private void deserializeAir(CompoundTag tag) {
        air.clear();unknownAir=null;
        if(tag.isEmpty()) return;
        if(tag.getInt("version")!=AIR_VERSION || !tag.contains("cells",Tag.TAG_LIST)) { unknownAir=tag.copy();return; }
        var list=tag.getList("cells",Tag.TAG_COMPOUND);
        var counts=new java.util.HashMap<LocalAirGeometryCapture.Shape,Integer>();
        if(list.size()>MAX_AIR_CELLS) { unknownAir=tag.copy();return; }
        for(int i=0;i<list.size();i++) {
            var cell=list.getCompound(i);var material=cell.getLongArray("material");var offsets=cell.getLongArray("offsets");
            try {
                if(!cell.contains("pos",Tag.TAG_LONG) || !cell.contains("tick",Tag.TAG_LONG) || !cell.contains("solid",Tag.TAG_INT_ARRAY) || !cell.contains("fluid",Tag.TAG_INT_ARRAY)) throw new IllegalArgumentException("Missing cell identity");
                if(material.length!=4) throw new IllegalArgumentException("Material metadata missing");
                var shape=new LocalAirGeometryCapture.Shape(cell.getIntArray("solid"),cell.getIntArray("fluid"));
                int states=counts.computeIfAbsent(shape,key->{var baked=key.bake();return baked.components()+(baked.solidVolume()>0?1:0)+(baked.fluidVolume()>0?1:0);});
                if(offsets.length!=states) throw new IllegalArgumentException("Thermal phase count mismatch");
                var m=new LocalAirThermalState.Material(shape,Double.longBitsToDouble(material[0]),Double.longBitsToDouble(material[1]),Double.longBitsToDouble(material[2]),Double.longBitsToDouble(material[3]));
                var saved=new LocalAirThermalState.Saved(m,cell.getLong("tick"),java.util.Arrays.stream(offsets).mapToDouble(Double::longBitsToDouble).toArray());
                if(air.putIfAbsent(cell.getLong("pos"),saved)!=null) throw new IllegalArgumentException("Duplicate saved cell");
            } catch(IllegalArgumentException e) { air.clear();unknownAir=tag.copy();return; }
        }
    }

    public boolean isEmpty() {
        return boxes.isEmpty() && values.isEmpty() && air.isEmpty() && unknownAir==null;
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
        if(!air.isEmpty() || unknownAir!=null) tag.put("local_air",serializeAir());
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        boxes.clear();
        values.clear();
        deserializeAir(tag.getCompound("local_air"));
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
