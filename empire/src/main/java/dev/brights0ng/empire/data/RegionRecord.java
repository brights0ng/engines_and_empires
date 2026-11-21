// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionType;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * A 3D plot of blocks inside an empire's staked land assigned to a {@link RegionType} (e.g. {@link RegionType#HOME}).
 * Stored in {@link EmpireSavedData} with cached bounds and touched chunks for fast dirty-marking and chunk-load checks.
 */
public final class RegionRecord {

    public static final int VERSION = 1;

    private final UUID id;
    private final UUID empireId;
    private final RegionType type;
    private final RegionOrigin origin;
    /** Packed {@link BlockKeys} / {@link BlockPos#asLong} seed coordinate (first bed / creation anchor). */
    private long seed;
    /** Packed {@link BlockKeys} / {@link BlockPos#asLong} coordinates belonging to this region. */
    private final LongOpenHashSet blocks = new LongOpenHashSet();
    /** Cached packed {@link dev.brights0ng.empire.rules.ChunkKeys} touched by {@link #blocks}. Not saved. */
    private final LongOpenHashSet chunks = new LongOpenHashSet();
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;
    /** Citizen record UUIDs currently attached to this region (e.g. HOME inhabitants). */
    private final Set<UUID> inhabitants = new LinkedHashSet<>();
    private boolean valid = true;
    private RegionInvalidReason invalidReason = RegionInvalidReason.NONE;
    private long invalidSinceGameTime = -1L;
    /** Transient dirty flag: set on nearby block changes or unstaking, cleared when updated. */
    private boolean dirty;
    /** Transient game time of the most recent update, for 1-second cooldown throttling. */
    private long lastUpdateGameTime = Long.MIN_VALUE;

    public RegionRecord(UUID id, UUID empireId, RegionType type, RegionOrigin origin, Collection<Long> initialBlocks) {
        this(id, empireId, type, origin, firstBlockOrZero(initialBlocks), initialBlocks);
    }

    public RegionRecord(UUID id, UUID empireId, RegionType type, RegionOrigin origin, long seed,
                        Collection<Long> initialBlocks) {
        this.id = id;
        this.empireId = empireId;
        this.type = type;
        this.origin = origin;
        this.seed = seed;
        for (long key : initialBlocks) {
            this.blocks.add(key);
        }
        recomputeBoundsAndChunks();
    }

    public RegionRecord(UUID id, UUID empireId, RegionType type, RegionOrigin origin, long[] initialBlocks) {
        this(id, empireId, type, origin, initialBlocks.length > 0 ? initialBlocks[0] : 0L, initialBlocks);
    }

    public RegionRecord(UUID id, UUID empireId, RegionType type, RegionOrigin origin, long seed,
                        long[] initialBlocks) {
        this.id = id;
        this.empireId = empireId;
        this.type = type;
        this.origin = origin;
        this.seed = seed;
        for (long key : initialBlocks) {
            this.blocks.add(key);
        }
        recomputeBoundsAndChunks();
    }

    private static long firstBlockOrZero(Collection<Long> blocks) {
        return blocks.isEmpty() ? 0L : blocks.iterator().next();
    }

    private void recomputeBoundsAndChunks() {
        chunks.clear();
        if (blocks.isEmpty()) {
            minX = minY = minZ = 0;
            maxX = maxY = maxZ = 0;
            return;
        }
        minX = minY = minZ = Integer.MAX_VALUE;
        maxX = maxY = maxZ = Integer.MIN_VALUE;
        LongIterator it = blocks.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
            chunks.add(BlockKeys.chunkKey(key));
        }
    }

    public UUID id() {
        return id;
    }

    public UUID empireId() {
        return empireId;
    }

    public RegionType type() {
        return type;
    }

    public RegionOrigin origin() {
        return origin;
    }

    public long seed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    /** Replaces the region's block set and recomputes its cached bounds and touched chunks. */
    public void replaceBlocks(Collection<Long> newBlocks) {
        this.blocks.clear();
        for (long key : newBlocks) {
            this.blocks.add(key);
        }
        recomputeBoundsAndChunks();
    }

    public int blockCount() {
        return blocks.size();
    }

    public LongSet blocks() {
        return LongSets.unmodifiable(blocks);
    }

    public LongSet chunks() {
        return LongSets.unmodifiable(chunks);
    }

    public boolean containsBlock(long packedPos) {
        return blocks.contains(packedPos);
    }

    public boolean containsBlock(BlockPos pos) {
        return blocks.contains(pos.asLong());
    }

    public boolean touchesChunk(long chunkKey) {
        return chunks.contains(chunkKey);
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /** True if {@code pos} lies within this region's bounding box expanded by {@code inflation} blocks on each side. */
    public boolean isWithinInflatedBounds(BlockPos pos, int inflation) {
        if (blocks.isEmpty()) {
            return false;
        }
        return pos.getX() >= minX - inflation && pos.getX() <= maxX + inflation
                && pos.getY() >= minY - inflation && pos.getY() <= maxY + inflation
                && pos.getZ() >= minZ - inflation && pos.getZ() <= maxZ + inflation;
    }

    public Set<UUID> inhabitants() {
        return Collections.unmodifiableSet(inhabitants);
    }

    public boolean addInhabitant(UUID civilianId) {
        return inhabitants.add(civilianId);
    }

    public boolean removeInhabitant(UUID civilianId) {
        return inhabitants.remove(civilianId);
    }

    public void clearInhabitants() {
        inhabitants.clear();
    }

    public boolean valid() {
        return valid;
    }

    public RegionInvalidReason invalidReason() {
        return invalidReason;
    }

    public long invalidSinceGameTime() {
        return invalidSinceGameTime;
    }

    public void markValid() {
        this.valid = true;
        this.invalidReason = RegionInvalidReason.NONE;
        this.invalidSinceGameTime = -1L;
    }

    /** Marks this region invalid, recording {@code gameTime} on the initial {@code VALID -> INVALID} transition. */
    public void markInvalid(RegionInvalidReason reason, long gameTime) {
        if (this.valid) {
            this.invalidSinceGameTime = gameTime;
        }
        this.valid = false;
        this.invalidReason = reason != null ? reason : RegionInvalidReason.NONE;
    }

    public boolean isDirtyFlag() {
        return dirty;
    }

    public void setDirtyFlag(boolean dirty) {
        this.dirty = dirty;
    }

    public long lastUpdateGameTime() {
        return lastUpdateGameTime;
    }

    public void setLastUpdateGameTime(long gameTime) {
        this.lastUpdateGameTime = gameTime;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putUUID("id", id);
        tag.putUUID("empire", empireId);
        tag.putString("type", type.id());
        tag.putString("origin", origin.id());
        tag.putLong("seed", seed);
        tag.put("blocks", new LongArrayTag(blocks.toLongArray()));
        ListTag inhabitantsTag = new ListTag();
        for (UUID inhabitant : inhabitants) {
            inhabitantsTag.add(NbtUtils.createUUID(inhabitant));
        }
        tag.put("inhabitants", inhabitantsTag);
        tag.putBoolean("valid", valid);
        tag.putString("invalid_reason", invalidReason.id());
        tag.putLong("invalid_since", invalidSinceGameTime);
        return tag;
    }

    @Nullable
    public static RegionRecord load(CompoundTag tag) {
        RegionType type = RegionType.byId(tag.getString("type"));
        RegionOrigin origin = RegionOrigin.byId(tag.getString("origin"));
        if (type == null || origin == null || !tag.hasUUID("id") || !tag.hasUUID("empire")) {
            return null;
        }
        long[] loadedBlocks = tag.getLongArray("blocks");
        long loadedSeed = tag.contains("seed", Tag.TAG_ANY_NUMERIC)
                ? tag.getLong("seed")
                : (loadedBlocks.length > 0 ? loadedBlocks[0] : 0L);
        RegionRecord record = new RegionRecord(
                tag.getUUID("id"),
                tag.getUUID("empire"),
                type,
                origin,
                loadedSeed,
                loadedBlocks);
        ListTag inhabitantsTag = tag.getList("inhabitants", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < inhabitantsTag.size(); i++) {
            record.inhabitants.add(NbtUtils.loadUUID(inhabitantsTag.get(i)));
        }
        record.valid = !tag.contains("valid") || tag.getBoolean("valid");
        record.invalidReason = RegionInvalidReason.byId(tag.getString("invalid_reason"));
        record.invalidSinceGameTime = tag.contains("invalid_since", Tag.TAG_ANY_NUMERIC)
                ? tag.getLong("invalid_since")
                : -1L;
        if (record.valid) {
            record.invalidReason = RegionInvalidReason.NONE;
            record.invalidSinceGameTime = -1L;
        }
        return record;
    }
}
