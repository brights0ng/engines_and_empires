// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRooms;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.RoomLabel;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * A 3D plot of blocks inside an empire's staked land assigned to a {@link RegionType} (e.g. {@link RegionType#HOME}).
 * Stored in {@link EmpireSavedData} with cached bounds and touched chunks for fast dirty-marking and chunk-load checks.
 */
public final class RegionRecord {

    public static final int VERSION = 3;

    /** One connected room inside a region, with its own seed, block set, {@link RoomLabel}s, and optional storage blocks. */
    public static final class RoomRecord {

        private final int index;
        private final long seed;
        private final LongOpenHashSet blocks;
        private final Set<RoomLabel> labels;
        private final LongOpenHashSet storageBlocks;

        public RoomRecord(int index, Collection<Long> blocks, Set<RoomLabel> labels) {
            this(index, firstBlockOrZero(blocks), blocks, labels, Collections.emptyList());
        }

        public RoomRecord(int index, long[] blocks, Set<RoomLabel> labels) {
            this(index, blocks.length > 0 ? blocks[0] : 0L, blocks, labels, new long[0]);
        }

        public RoomRecord(int index, long seed, Collection<Long> blocks, Set<RoomLabel> labels,
                          Collection<Long> storageBlocks) {
            this.index = index;
            this.seed = seed;
            this.blocks = new LongOpenHashSet(blocks.size());
            for (long key : blocks) {
                this.blocks.add(key);
            }
            this.labels = labels.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(labels));
            this.storageBlocks = new LongOpenHashSet(storageBlocks.size());
            for (long key : storageBlocks) {
                this.storageBlocks.add(key);
            }
        }

        public RoomRecord(int index, long seed, long[] blocks, Set<RoomLabel> labels, long[] storageBlocks) {
            this.index = index;
            this.seed = seed;
            this.blocks = new LongOpenHashSet(blocks);
            this.labels = labels.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(labels));
            this.storageBlocks = new LongOpenHashSet(storageBlocks);
        }

        public int index() {
            return index;
        }

        public long seed() {
            return seed;
        }

        public int blockCount() {
            return blocks.size();
        }

        public LongSet blocks() {
            return LongSets.unmodifiable(blocks);
        }

        public Set<RoomLabel> labels() {
            return labels;
        }

        public boolean hasLabel(RoomLabel label) {
            return labels.contains(label);
        }

        public boolean isIndoor() {
            return labels.contains(RoomLabel.INDOOR);
        }

        public boolean isOutdoor() {
            return labels.contains(RoomLabel.OUTDOOR);
        }

        public boolean isShed() {
            return labels.contains(RoomLabel.SHED);
        }

        public boolean isField() {
            return labels.contains(RoomLabel.FIELD);
        }

        public LongSet storageBlocks() {
            return LongSets.unmodifiable(storageBlocks);
        }

        public int storageCount() {
            return storageBlocks.size();
        }

        public boolean isStorage(long packedPos) {
            return storageBlocks.contains(packedPos);
        }

        public boolean isStorage(BlockPos pos) {
            return storageBlocks.contains(pos.asLong());
        }
    }

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
    /** Connected rooms partitioned out of {@link #blocks} (excluding {@link #separators}). */
    private final List<RoomRecord> rooms = new ArrayList<>();
    /** Packed coordinates of room-separator blocks inside {@link #blocks} (belong to no room). */
    private final LongOpenHashSet separators = new LongOpenHashSet();
    /** Packed coordinates of separator blocks severed by manual room removal so auto-reshape does not re-absorb them. */
    private final LongOpenHashSet blockedSeparators = new LongOpenHashSet();
    /** Aggregated packed coordinates of storage blocks across this region's rooms (rebuilt from {@link #rooms}). */
    private final LongOpenHashSet storageBlocks = new LongOpenHashSet();
    /** Transient lookup: packed block coordinate -> index in {@link #rooms}, or {@code -1} if separator/unpartitioned. */
    private final Long2IntOpenHashMap blockToRoom = new Long2IntOpenHashMap();
    {
        blockToRoom.defaultReturnValue(-1);
    }
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
    /** Transient revision bumped when blocks in/adjacent to this region change or its shape/validity updates. */
    private long revision;
    /** Cached walkable indoor floor block count; {@code -1} when needing recomputation. */
    private int cachedIndoorFloorArea = -1;

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
        cachedIndoorFloorArea = -1;
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

    /** Replaces the room partition and rebuilds the block-to-room lookup index. Returns true if rooms or separators changed. */
    public boolean setRooms(RegionRooms.Partition partition) {
        boolean changed = !roomsMatch(partition);
        cachedIndoorFloorArea = -1;
        rooms.clear();
        separators.clear();
        storageBlocks.clear();
        blockToRoom.clear();
        if (partition == null) {
            return changed;
        }
        for (long sep : partition.separators()) {
            separators.add(sep);
        }
        for (RegionRooms.Room room : partition.rooms()) {
            RoomRecord rec = new RoomRecord(rooms.size(), room.blocks(), room.labels());
            int idx = rec.index();
            rooms.add(rec);
            LongIterator it = rec.blocks.iterator();
            while (it.hasNext()) {
                blockToRoom.put(it.nextLong(), idx);
            }
        }
        return changed;
    }

    /**
     * Replaces this region's rooms directly with explicit {@link RoomRecord}s (preserving per-room seeds and marked
     * storage blocks, e.g. for {@link RegionType#FARM}). Returns true if rooms, separators, seeds, or storage changed.
     */
    public boolean setRoomRecords(List<RoomRecord> newRooms, Collection<Long> newSeparators) {
        boolean changed = !roomRecordsMatch(newRooms, newSeparators);
        cachedIndoorFloorArea = -1;
        rooms.clear();
        separators.clear();
        storageBlocks.clear();
        blockToRoom.clear();
        if (newSeparators != null) {
            for (long sep : newSeparators) {
                separators.add(sep);
            }
        }
        if (newRooms != null) {
            for (RoomRecord incoming : newRooms) {
                int idx = rooms.size();
                RoomRecord rec = incoming.index == idx
                        ? incoming
                        : new RoomRecord(idx, incoming.seed, incoming.blocks, incoming.labels, incoming.storageBlocks);
                rooms.add(rec);
                LongIterator it = rec.blocks.iterator();
                while (it.hasNext()) {
                    blockToRoom.put(it.nextLong(), idx);
                }
                LongIterator sit = rec.storageBlocks.iterator();
                while (sit.hasNext()) {
                    storageBlocks.add(sit.nextLong());
                }
            }
        }
        return changed;
    }

    private boolean roomRecordsMatch(List<RoomRecord> incomingRooms, Collection<Long> incomingSeparators) {
        int sepCount = incomingSeparators == null ? 0 : incomingSeparators.size();
        int roomCount = incomingRooms == null ? 0 : incomingRooms.size();
        if (separators.size() != sepCount || rooms.size() != roomCount) {
            return false;
        }
        if (incomingSeparators != null) {
            for (long sep : incomingSeparators) {
                if (!separators.contains(sep)) {
                    return false;
                }
            }
        }
        if (incomingRooms != null) {
            for (RoomRecord incoming : incomingRooms) {
                if (incoming.blocks.isEmpty()) {
                    continue;
                }
                long sample = incoming.blocks.iterator().nextLong();
                int existingIdx = blockToRoom.get(sample);
                if (existingIdx < 0 || existingIdx >= rooms.size()) {
                    return false;
                }
                RoomRecord existing = rooms.get(existingIdx);
                if (existing.blockCount() != incoming.blockCount()
                        || existing.seed != incoming.seed
                        || !existing.labels.equals(incoming.labels)
                        || !existing.storageBlocks.equals(incoming.storageBlocks)) {
                    return false;
                }
                LongIterator it = incoming.blocks.iterator();
                while (it.hasNext()) {
                    if (!existing.blocks.contains(it.nextLong())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** True if this region's current rooms and separators match {@code partition} (ignoring room index ordering). */
    private boolean roomsMatch(RegionRooms.Partition partition) {
        if (partition == null) {
            return rooms.isEmpty() && separators.isEmpty();
        }
        if (separators.size() != partition.separators().size()
                || rooms.size() != partition.rooms().size()) {
            return false;
        }
        for (long sep : partition.separators()) {
            if (!separators.contains(sep)) {
                return false;
            }
        }
        for (RegionRooms.Room incoming : partition.rooms()) {
            if (incoming.blocks().isEmpty()) {
                continue;
            }
            long sample = incoming.blocks().iterator().next();
            int existingIdx = blockToRoom.get(sample);
            if (existingIdx < 0 || existingIdx >= rooms.size()) {
                return false;
            }
            RoomRecord existing = rooms.get(existingIdx);
            if (existing.blockCount() != incoming.size()
                    || !existing.labels().equals(incoming.labels())) {
                return false;
            }
            for (long key : incoming.blocks()) {
                if (!existing.blocks.contains(key)) {
                    return false;
                }
            }
        }
        return true;
    }

    public List<RoomRecord> rooms() {
        return Collections.unmodifiableList(rooms);
    }

    public LongSet separators() {
        return LongSets.unmodifiable(separators);
    }

    /** Separator blocks severed by manual room removal; treated as flood-fill boundaries during auto-reshape. */
    public LongSet blockedSeparators() {
        return LongSets.unmodifiable(blockedSeparators);
    }

    public void addBlockedSeparators(Collection<Long> positions) {
        for (long key : positions) {
            blockedSeparators.add(key);
        }
    }

    /** Packed coordinates of all blocks marked as storage across this region's rooms (e.g. chests/barrels/vaults in farm sheds). */
    public LongSet storageBlocks() {
        return LongSets.unmodifiable(storageBlocks);
    }

    public boolean isStorageBlock(long packedPos) {
        return storageBlocks.contains(packedPos);
    }

    public boolean isStorageBlock(BlockPos pos) {
        return storageBlocks.contains(pos.asLong());
    }

    public int roomCountWithLabel(RoomLabel label) {
        int count = 0;
        for (RoomRecord room : rooms) {
            if (room.hasLabel(label)) {
                count++;
            }
        }
        return count;
    }

    /** Blocks belonging to rooms tagged with {@code label} (used for per-label region rendering). */
    public long[] renderBlocksForLabel(RoomLabel label) {
        int count = 0;
        for (RoomRecord room : rooms) {
            if (room.hasLabel(label)) {
                count += room.blockCount();
            }
        }
        if (count == 0) {
            return new long[0];
        }
        long[] out = new long[count];
        int pos = 0;
        for (RoomRecord room : rooms) {
            if (room.hasLabel(label)) {
                LongIterator it = room.blocks.iterator();
                while (it.hasNext()) {
                    out[pos++] = it.nextLong();
                }
            }
        }
        return out;
    }

    public boolean isSeparator(long packedPos) {
        return separators.contains(packedPos);
    }

    public boolean isSeparator(BlockPos pos) {
        return separators.contains(pos.asLong());
    }

    @Nullable
    public RoomRecord roomAt(long packedPos) {
        int idx = blockToRoom.get(packedPos);
        return idx >= 0 && idx < rooms.size() ? rooms.get(idx) : null;
    }

    @Nullable
    public RoomRecord roomAt(BlockPos pos) {
        return roomAt(pos.asLong());
    }

    public boolean hasRoomLabel(long packedPos, RoomLabel label) {
        RoomRecord room = roomAt(packedPos);
        return room != null && room.hasLabel(label);
    }

    public boolean hasRoomLabel(BlockPos pos, RoomLabel label) {
        return hasRoomLabel(pos.asLong(), label);
    }

    public boolean isOutdoorBlock(long packedPos) {
        return hasRoomLabel(packedPos, RoomLabel.OUTDOOR);
    }

    public boolean isOutdoorBlock(BlockPos pos) {
        return hasRoomLabel(pos.asLong(), RoomLabel.OUTDOOR);
    }

    /**
     * True if {@code packedPos} belongs to a non-separator, non-outdoor room of this region (or, for legacy
     * unpartitioned regions without room data, is contained in {@link #blocks}).
     */
    public boolean isIndoorBlock(long packedPos) {
        if (rooms.isEmpty() && separators.isEmpty()) {
            return blocks.contains(packedPos);
        }
        RoomRecord room = roomAt(packedPos);
        return room != null && !room.isOutdoor();
    }

    public boolean isIndoorBlock(BlockPos pos) {
        return isIndoorBlock(pos.asLong());
    }

    /**
     * Effective usable volume of this region: sum of block counts across all non-outdoor rooms (excluding
     * {@link RoomLabel#OUTDOOR} rooms and separator blocks). Falls back to {@link #blockCount()} for legacy saves
     * that have not yet been partitioned into rooms.
     */
    public int effectiveVolume() {
        if (rooms.isEmpty() && separators.isEmpty()) {
            return blocks.size();
        }
        int total = 0;
        for (RoomRecord room : rooms) {
            if (!room.isOutdoor()) {
                total += room.blockCount();
            }
        }
        return total;
    }

    /**
     * Walkable indoor floor block count: indoor cells {@code (x, y, z)} resting above a non-indoor floor surface
     * ({@code !isIndoorBlock(x, y - 1, z)}) with at least two blocks of indoor headroom
     * ({@code isIndoorBlock(x, y + 1, z)}). Cached until blocks or rooms change.
     */
    public int indoorFloorArea() {
        if (cachedIndoorFloorArea >= 0) {
            return cachedIndoorFloorArea;
        }
        int count = 0;
        LongIterator it = blocks.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (!isIndoorBlock(key)) {
                continue;
            }
            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);
            if (!isIndoorBlock(BlockKeys.pack(x, y - 1, z)) && isIndoorBlock(BlockKeys.pack(x, y + 1, z))) {
                count++;
            }
        }
        cachedIndoorFloorArea = count;
        return count;
    }

    /** Blocks to render for the region veil: only non-outdoor rooms when valid; non-separator blocks when invalid so the warning outline stays visible. */
    public long[] renderBlocks() {
        if (rooms.isEmpty() && separators.isEmpty()) {
            return blocks.toLongArray();
        }
        if (!valid) {
            LongOpenHashSet out = new LongOpenHashSet(blocks.size());
            LongIterator it = blocks.iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                if (!separators.contains(key)) {
                    out.add(key);
                }
            }
            return out.toLongArray();
        }
        int count = effectiveVolume();
        if (count == 0) {
            return new long[0];
        }
        long[] out = new long[count];
        int pos = 0;
        for (RoomRecord room : rooms) {
            if (!room.isOutdoor()) {
                LongIterator it = room.blocks.iterator();
                while (it.hasNext()) {
                    out[pos++] = it.nextLong();
                }
            }
        }
        return out;
    }

    /** Separator blocks to render with a yellow highlight. */
    public long[] renderSeparators() {
        return separators.isEmpty() ? new long[0] : separators.toLongArray();
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

    public long revision() {
        return revision;
    }

    public void bumpRevision() {
        this.revision++;
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
        if (!separators.isEmpty()) {
            tag.put("separators", new LongArrayTag(separators.toLongArray()));
        }
        if (!blockedSeparators.isEmpty()) {
            tag.put("blocked_separators", new LongArrayTag(blockedSeparators.toLongArray()));
        }
        if (!rooms.isEmpty()) {
            ListTag roomsTag = new ListTag();
            for (RoomRecord room : rooms) {
                CompoundTag rt = new CompoundTag();
                rt.putLong("seed", room.seed);
                rt.put("blocks", new LongArrayTag(room.blocks.toLongArray()));
                ListTag labelsTag = new ListTag();
                for (RoomLabel label : room.labels) {
                    labelsTag.add(StringTag.valueOf(label.id()));
                }
                rt.put("labels", labelsTag);
                if (!room.storageBlocks.isEmpty()) {
                    rt.put("storage", new LongArrayTag(room.storageBlocks.toLongArray()));
                }
                roomsTag.add(rt);
            }
            tag.put("rooms", roomsTag);
        }
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
        if (tag.contains("separators", Tag.TAG_LONG_ARRAY)) {
            for (long sep : tag.getLongArray("separators")) {
                record.separators.add(sep);
            }
        }
        if (tag.contains("blocked_separators", Tag.TAG_LONG_ARRAY)) {
            for (long sep : tag.getLongArray("blocked_separators")) {
                record.blockedSeparators.add(sep);
            }
        }
        if (tag.contains("rooms", Tag.TAG_LIST)) {
            ListTag roomsTag = tag.getList("rooms", Tag.TAG_COMPOUND);
            for (int i = 0; i < roomsTag.size(); i++) {
                CompoundTag rt = roomsTag.getCompound(i);
                long[] roomBlocks = rt.getLongArray("blocks");
                long roomSeed = rt.contains("seed", Tag.TAG_ANY_NUMERIC)
                        ? rt.getLong("seed")
                        : (roomBlocks.length > 0 ? roomBlocks[0] : 0L);
                EnumSet<RoomLabel> labels = EnumSet.noneOf(RoomLabel.class);
                ListTag labelsTag = rt.getList("labels", Tag.TAG_STRING);
                for (int j = 0; j < labelsTag.size(); j++) {
                    RoomLabel label = RoomLabel.byId(labelsTag.getString(j));
                    if (label != null) {
                        labels.add(label);
                    }
                }
                long[] roomStorage = rt.contains("storage", Tag.TAG_LONG_ARRAY)
                        ? rt.getLongArray("storage")
                        : new long[0];
                RoomRecord room = new RoomRecord(record.rooms.size(), roomSeed, roomBlocks, labels, roomStorage);
                int idx = room.index();
                record.rooms.add(room);
                LongIterator it = room.blocks.iterator();
                while (it.hasNext()) {
                    record.blockToRoom.put(it.nextLong(), idx);
                }
                for (long s : roomStorage) {
                    record.storageBlocks.add(s);
                }
            }
        }
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
        record.cachedIndoorFloorArea = -1;
        return record;
    }
}
