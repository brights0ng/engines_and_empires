// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianOccupations;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.RegionFloodFill;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.RoomLabel;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Flood-fill scanner, room manager, and validator for {@link RegionType#FARM} regions.
 * <p>
 * A farm consists of independent outdoor workstation rooms tagged either {@link RoomLabel#SHED} (cap
 * {@value RegionRules#FARM_SHED_MAX_BLOCKS}: composters, chests, trapped chests, barrels, Create item vaults — no air)
 * or {@link RoomLabel#FIELD} (cap {@value RegionRules#FARM_FIELD_MAX_BLOCKS}: farmland, crops, and the block directly
 * above any eligible farmland regardless of type — no general air). Touching rooms with the same label merge; rooms
 * with different labels act as boundaries for each other. Storage blocks inside {@code SHED} rooms are marked so
 * civilians can target them later.
 */
public final class FarmRegionScanner {

    /** Whitelisted blocks for {@code FARM} {@link RoomLabel#SHED} rooms ({@code empire:region_farm_shed}). */
    public static final TagKey<Block> SHED_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Empire.MODID, "region_farm_shed"));

    /** Blocks marked as accessible storage inside shed/storage rooms ({@code empire:region_storage}). */
    public static final TagKey<Block> STORAGE_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Empire.MODID, "region_storage"));

    /** Whitelisted blocks for {@code FARM} {@link RoomLabel#FIELD} rooms ({@code empire:region_farm_field}). */
    public static final TagKey<Block> FIELD_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Empire.MODID, "region_farm_field"));

    /** Hazard blocks that abort {@code FARM} flood fills and invalidate existing farms ({@code empire:region_farm_hazards}). */
    public static final TagKey<Block> HAZARDS_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Empire.MODID, "region_farm_hazards"));

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    public record ScanResult(@Nullable RegionRecord region, RegionInvalidReason failure) {

        public boolean succeeded() {
            return region != null && failure == RegionInvalidReason.NONE;
        }
    }

    public record AddRoomResult(@Nullable RegionRecord.RoomRecord room, boolean merged, RegionInvalidReason failure) {

        public boolean succeeded() {
            return room != null && failure == RegionInvalidReason.NONE;
        }
    }

    public enum RemoveOutcome {
        NONE,
        REMOVED_ROOM,
        DELETED_REGION
    }

    public record RemoveRoomResult(RemoveOutcome outcome, @Nullable RoomLabel removedLabel) {
    }

    public record RescanResult(Set<Long> blocks, List<RegionRecord.RoomRecord> rooms, long seed,
                               RegionInvalidReason failure) {

        public boolean succeeded() {
            return failure == RegionInvalidReason.NONE && !blocks.isEmpty() && !rooms.isEmpty();
        }
    }

    /** True if {@code state} is a composter (the home/anchor block for {@link RegionType#FARM}). */
    public static boolean isComposter(BlockState state) {
        return state.is(Blocks.COMPOSTER);
    }

    /** True if {@code state} is whitelisted for a {@link RoomLabel#SHED} room. */
    public static boolean isShedBlock(BlockState state) {
        return state.is(SHED_TAG);
    }

    /** True if {@code state} is marked as civilian-accessible storage when inside a {@link RoomLabel#SHED} room. */
    public static boolean isStorageBlock(BlockState state) {
        return state.is(STORAGE_TAG);
    }

    /** True if {@code state} is tilled farmland (vanilla farmland or modded {@link FarmBlock} subclasses). */
    public static boolean isFarmland(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.getBlock() instanceof FarmBlock;
    }

    /** True if {@code state} is directly whitelisted for a {@link RoomLabel#FIELD} room (farmland or crop). */
    public static boolean isDirectFieldBlock(BlockState state) {
        return isFarmland(state) || state.is(FIELD_TAG) || state.getBlock() instanceof CropBlock;
    }

    /** True if {@code state} is a hazard for farm regions. */
    public static boolean isHazard(BlockState state) {
        return state.is(HAZARDS_TAG);
    }

    /** Returns {@link RoomLabel#SHED} or {@link RoomLabel#FIELD} for a directly clickable farm block, or {@code null}. */
    @Nullable
    public static RoomLabel labelForClickedBlock(BlockState state) {
        if (isShedBlock(state)) {
            return RoomLabel.SHED;
        }
        if (isDirectFieldBlock(state)) {
            return RoomLabel.FIELD;
        }
        return null;
    }

    /**
     * Scans a brand-new {@link RegionType#FARM} seeded at a composter at {@code start}.
     * Does not register the region in {@code data}; the caller decides whether to persist it.
     */
    public static ScanResult scanNewFarm(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                         BlockPos start, RegionOrigin origin) {
        if (!level.hasChunkAt(start) || !isComposter(level.getBlockState(start))) {
            return new ScanResult(null, RegionInvalidReason.NO_SHED);
        }
        long chunk = ChunkKeys.pack(start.getX() >> 4, start.getZ() >> 4);
        if (!empire.state().staked().contains(chunk)) {
            return new ScanResult(null, RegionInvalidReason.UNSTAKED);
        }
        if (data.regionAt(start) != null) {
            return new ScanResult(null, RegionInvalidReason.OVERLAPS_EXISTING);
        }

        RegionFloodFill.Result fill = fillRoom(
                level, data, empire,
                start.getX(), start.getY(), start.getZ(),
                RoomLabel.SHED, null, LongSets.EMPTY_SET);
        if (!fill.succeeded()) {
            return new ScanResult(null, fill.failure());
        }

        List<Long> storage = collectStorageBlocks(level, fill.blocks());
        RegionRecord candidate = new RegionRecord(
                UUID.randomUUID(), empire.id(), RegionType.FARM, origin, start.asLong(), fill.blocks());
        RegionRecord.RoomRecord shed = new RegionRecord.RoomRecord(
                0, start.asLong(), fill.blocks(), EnumSet.of(RoomLabel.SHED), storage);
        candidate.setRoomRecords(List.of(shed), Set.of());
        return new ScanResult(candidate, RegionInvalidReason.NONE);
    }

    /**
     * Flood-fills a new {@link RoomLabel#SHED} or {@link RoomLabel#FIELD} room from {@code start} and adds it to
     * {@code farm}, merging with any touching existing rooms of {@code farm} that share the same label and treating
     * rooms of the opposite label as boundaries.
     */
    public static AddRoomResult addRoom(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                        RegionRecord farm, BlockPos start) {
        if (!level.hasChunkAt(start)) {
            return new AddRoomResult(null, false, RegionInvalidReason.UNSTAKED);
        }
        BlockState startState = level.getBlockState(start);
        RoomLabel label = labelForClickedBlock(startState);
        if (label == null) {
            return new AddRoomResult(null, false, RegionInvalidReason.TOO_SMALL);
        }
        long chunk = ChunkKeys.pack(start.getX() >> 4, start.getZ() >> 4);
        if (!empire.state().staked().contains(chunk)) {
            return new AddRoomResult(null, false, RegionInvalidReason.UNSTAKED);
        }
        RegionRecord existingAtStart = data.regionAt(start);
        if (existingAtStart != null && !existingAtStart.id().equals(farm.id())) {
            return new AddRoomResult(null, false, RegionInvalidReason.OVERLAPS_EXISTING);
        }

        LongOpenHashSet otherTagBoundary = blocksOfOtherLabel(farm, label);
        if (otherTagBoundary.contains(start.asLong())) {
            return new AddRoomResult(null, false, RegionInvalidReason.OVERLAPS_EXISTING);
        }

        RegionFloodFill.Result fill = fillRoom(
                level, data, empire,
                start.getX(), start.getY(), start.getZ(),
                label, farm.id(), otherTagBoundary);
        if (!fill.succeeded()) {
            return new AddRoomResult(null, false, fill.failure());
        }

        Set<Long> filled = fill.blocks();
        List<RegionRecord.RoomRecord> updatedRooms = new ArrayList<>();
        boolean merged = false;
        long chosenSeed = start.asLong();
        boolean hasComposterSeed = label == RoomLabel.SHED && isComposter(startState);
        String preservedCrop = null;

        for (RegionRecord.RoomRecord existing : farm.rooms()) {
            if (!existing.hasLabel(label)) {
                updatedRooms.add(existing);
                continue;
            }
            if (sharesAnyBlock(existing.blocks(), filled)) {
                merged = true;
                if (preservedCrop == null && existing.preferredCropId() != null) {
                    preservedCrop = existing.preferredCropId();
                }
                // Prefer preserving an existing composter seed (for sheds) or existing room seed if still present.
                if (filled.contains(existing.seed())) {
                    BlockPos existingSeedPos = BlockPos.of(existing.seed());
                    boolean existingIsComposter = label == RoomLabel.SHED
                            && level.hasChunkAt(existingSeedPos)
                            && isComposter(level.getBlockState(existingSeedPos));
                    if (!hasComposterSeed || existingIsComposter) {
                        chosenSeed = existing.seed();
                        hasComposterSeed = existingIsComposter;
                    }
                }
            } else {
                updatedRooms.add(existing);
            }
        }

        List<Long> storage = label == RoomLabel.SHED ? collectStorageBlocks(level, filled) : List.of();
        RegionRecord.RoomRecord addedRoom = new RegionRecord.RoomRecord(
                updatedRooms.size(), chosenSeed, filled, EnumSet.of(label), storage,
                label == RoomLabel.FIELD ? preservedCrop : null);
        updatedRooms.add(addedRoom);

        Set<Long> allBlocks = unionRoomBlocks(updatedRooms);
        data.replaceRegionBlocks(farm, allBlocks);
        farm.setRoomRecords(updatedRooms, Set.of());
        farm.setSeed(pickFarmSeed(level, updatedRooms, farm.seed()));
        farm.bumpRevision();
        data.setDirty();

        // Re-validate immediately if the farm was previously invalid (e.g. adding a shed to a NO_SHED farm).
        if (!farm.valid()) {
            RegionManager.updateRegion(level, data, farm, level.getGameTime());
        }
        return new AddRoomResult(farm.roomAt(start), merged, RegionInvalidReason.NONE);
    }

    /**
     * Removes the room containing {@code pos} from {@code farm}, deleting {@code farm} if no rooms remain.
     */
    public static RemoveRoomResult removeRoomAt(ServerLevel level, EmpireSavedData data, RegionRecord farm,
                                                BlockPos pos, long gameTime) {
        RegionRecord.RoomRecord target = farm.roomAt(pos);
        if (target == null) {
            return new RemoveRoomResult(RemoveOutcome.NONE, null);
        }
        RoomLabel removedLabel = target.isShed() ? RoomLabel.SHED : (target.isField() ? RoomLabel.FIELD : null);

        List<RegionRecord.RoomRecord> remaining = new ArrayList<>();
        for (RegionRecord.RoomRecord room : farm.rooms()) {
            if (room != target) {
                remaining.add(room);
            }
        }

        if (remaining.isEmpty()) {
            RegionManager.detachAllInhabitants(level, data, farm);
            CivilianOccupations.detachAllWorkers(level, data, farm);
            data.removeRegion(farm.id());
            return new RemoveRoomResult(RemoveOutcome.DELETED_REGION, removedLabel);
        }

        Set<Long> allBlocks = unionRoomBlocks(remaining);
        data.replaceRegionBlocks(farm, allBlocks);
        farm.setRoomRecords(remaining, Set.of());
        farm.setSeed(pickFarmSeed(level, remaining, farm.seed()));
        farm.bumpRevision();
        data.setDirty();

        // Immediately re-validate so removing the last shed transitions the farm to INVALID(NO_SHED).
        RegionManager.updateRegion(level, data, farm, gameTime);
        return new RemoveRoomResult(RemoveOutcome.REMOVED_ROOM, removedLabel);
    }

    /**
     * Auto-reshapes every {@link RoomLabel#SHED} and {@link RoomLabel#FIELD} room in {@code farm}.
     * Automatic rescans preserve {@code FIELD} room size (never shrinking trampled/harvested soil; Bright, 2026-11-26)
     * while still allowing fields to expand/merge; explicit ledger rescans pass {@code allowShrinkFields = true}.
     */
    public static RescanResult rescan(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                      RegionRecord farm) {
        return rescan(level, data, empire, farm, false);
    }

    public static RescanResult rescan(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                      RegionRecord farm, boolean allowShrinkFields) {
        List<RegionRecord.RoomRecord> oldSheds = new ArrayList<>();
        List<RegionRecord.RoomRecord> oldFields = new ArrayList<>();
        for (RegionRecord.RoomRecord room : farm.rooms()) {
            if (room.isShed()) {
                oldSheds.add(room);
            } else if (room.isField()) {
                oldFields.add(room);
            }
        }

        // Only treat old field blocks that are STILL active field cells as boundaries when rescanning sheds, so
        // replacing a broken field block with a shed block lets an adjacent shed expand into it cleanly.
        LongOpenHashSet activeOldFieldBlocks = collectActiveFieldBlocks(level, oldFields);

        // 1. Rescan SHED rooms (merging any that now touch).
        LongOpenHashSet coveredShedBlocks = new LongOpenHashSet();
        List<RegionRecord.RoomRecord> newSheds = new ArrayList<>();
        boolean allowRepairAdjacent = !farm.valid();

        for (RegionRecord.RoomRecord oldShed : oldSheds) {
            long seed = resolveShedSeed(level, data, empire, farm.id(), oldShed, allowRepairAdjacent);
            if (seed == Long.MIN_VALUE) {
                continue;
            }
            if (coveredShedBlocks.contains(seed)) {
                continue;
            }
            if (!empire.state().staked().contains(BlockKeys.chunkKey(seed))) {
                return new RescanResult(Set.of(), List.of(), farm.seed(), RegionInvalidReason.UNSTAKED);
            }
            RegionFloodFill.Result fill = fillRoom(
                    level, data, empire,
                    BlockKeys.getX(seed), BlockKeys.getY(seed), BlockKeys.getZ(seed),
                    RoomLabel.SHED, farm.id(), activeOldFieldBlocks);
            if (!fill.succeeded()) {
                return new RescanResult(Set.of(), List.of(), farm.seed(), fill.failure());
            }
            for (long key : fill.blocks()) {
                coveredShedBlocks.add(key);
            }
            List<Long> storage = collectStorageBlocks(level, fill.blocks());
            newSheds.add(new RegionRecord.RoomRecord(
                    newSheds.size(), seed, fill.blocks(), EnumSet.of(RoomLabel.SHED), storage));
        }

        // If an INVALID(NO_SHED) farm has no old shed rooms left (e.g. last shed was shift-removed) and a player
        // places a composter adjacent to an existing field, recover a shed room from that composter.
        if (newSheds.isEmpty() && allowRepairAdjacent && !oldFields.isEmpty()) {
            long recoveredSeed = findAdjacentComposter(level, data, empire, farm.id(), oldFields);
            if (recoveredSeed != Long.MIN_VALUE) {
                RegionFloodFill.Result fill = fillRoom(
                        level, data, empire,
                        BlockKeys.getX(recoveredSeed), BlockKeys.getY(recoveredSeed), BlockKeys.getZ(recoveredSeed),
                        RoomLabel.SHED, farm.id(), activeOldFieldBlocks);
                if (!fill.succeeded()) {
                    return new RescanResult(Set.of(), List.of(), farm.seed(), fill.failure());
                }
                for (long key : fill.blocks()) {
                    coveredShedBlocks.add(key);
                }
                List<Long> storage = collectStorageBlocks(level, fill.blocks());
                newSheds.add(new RegionRecord.RoomRecord(
                        0, recoveredSeed, fill.blocks(), EnumSet.of(RoomLabel.SHED), storage));
            }
        }

        // 2. Rescan FIELD rooms (stopping at newly rescanned SHED blocks; merging any fields that now touch).
        // When allowShrinkFields is false (auto-rescan on block edits), existing field blocks are preserved so trampled
        // or harvested soil stays part of the field until explicitly re-scanned with the ledger (Bright, 2026-11-26).
        LongOpenHashSet coveredFieldBlocks = new LongOpenHashSet();
        List<RegionRecord.RoomRecord> newFields = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();

        for (RegionRecord.RoomRecord oldField : oldFields) {
            if (allowShrinkFields) {
                long seed = resolveFieldSeed(level, data, empire, farm.id(), oldField, coveredShedBlocks);
                if (seed == Long.MIN_VALUE) {
                    continue;
                }
                if (coveredFieldBlocks.contains(seed) || coveredShedBlocks.contains(seed)) {
                    continue;
                }
                if (!empire.state().staked().contains(BlockKeys.chunkKey(seed))) {
                    return new RescanResult(Set.of(), List.of(), farm.seed(), RegionInvalidReason.UNSTAKED);
                }
                RegionFloodFill.Result fill = fillRoom(
                        level, data, empire,
                        BlockKeys.getX(seed), BlockKeys.getY(seed), BlockKeys.getZ(seed),
                        RoomLabel.FIELD, farm.id(), coveredShedBlocks);
                if (!fill.succeeded()) {
                    return new RescanResult(Set.of(), List.of(), farm.seed(), fill.failure());
                }
                String crop = findPreservedCrop(oldFields, fill.blocks(), oldField.preferredCropId());
                for (long key : fill.blocks()) {
                    coveredFieldBlocks.add(key);
                }
                newFields.add(new RegionRecord.RoomRecord(
                        newSheds.size() + newFields.size(), seed, fill.blocks(), EnumSet.of(RoomLabel.FIELD),
                        List.of(), crop));
            } else {
                Set<Long> retained = new HashSet<>();
                LongIterator oldIt = oldField.blocks().iterator();
                while (oldIt.hasNext()) {
                    long key = oldIt.nextLong();
                    if (coveredShedBlocks.contains(key)) {
                        continue;
                    }
                    if (!empire.state().staked().contains(BlockKeys.chunkKey(key))) {
                        return new RescanResult(Set.of(), List.of(), farm.seed(), RegionInvalidReason.UNSTAKED);
                    }
                    RegionRecord existingOwner = data.regionAt(key);
                    if (existingOwner != null && !existingOwner.id().equals(farm.id())) {
                        continue;
                    }
                    int x = BlockKeys.getX(key);
                    int y = BlockKeys.getY(key);
                    int z = BlockKeys.getZ(key);
                    cursor.set(x, y, z);
                    if (level.hasChunkAt(cursor)) {
                        if (isHazard(level.getBlockState(cursor))) {
                            return new RescanResult(Set.of(), List.of(), farm.seed(), RegionInvalidReason.HAZARD);
                        }
                        for (int i = 0; i < 6; i++) {
                            neighbor.set(x + DX[i], y + DY[i], z + DZ[i]);
                            if (level.hasChunkAt(neighbor) && isHazard(level.getBlockState(neighbor))) {
                                return new RescanResult(Set.of(), List.of(), farm.seed(), RegionInvalidReason.HAZARD);
                            }
                        }
                    }
                    retained.add(key);
                }
                if (retained.isEmpty()) {
                    continue;
                }
                // Expand outward from any live farmland/crop cells in this field so newly tilled extensions auto-attach.
                LongOpenHashSet expandedSeeds = new LongOpenHashSet();
                LongIterator expandIt = oldField.blocks().iterator();
                while (expandIt.hasNext()) {
                    long key = expandIt.nextLong();
                    if (coveredShedBlocks.contains(key) || expandedSeeds.contains(key)) {
                        continue;
                    }
                    cursor.set(BlockKeys.getX(key), BlockKeys.getY(key), BlockKeys.getZ(key));
                    if (!level.hasChunkAt(cursor) || !isDirectFieldBlock(level.getBlockState(cursor))) {
                        continue;
                    }
                    RegionFloodFill.Result expand = fillRoom(
                            level, data, empire,
                            cursor.getX(), cursor.getY(), cursor.getZ(),
                            RoomLabel.FIELD, farm.id(), coveredShedBlocks);
                    if (!expand.succeeded()) {
                        return new RescanResult(Set.of(), List.of(), farm.seed(), expand.failure());
                    }
                    for (long ek : expand.blocks()) {
                        expandedSeeds.add(ek);
                        retained.add(ek);
                    }
                }
                long liveSeed = resolveFieldSeed(level, data, empire, farm.id(), oldField, coveredShedBlocks);
                if (retained.size() > RegionRules.FARM_FIELD_MAX_BLOCKS) {
                    return new RescanResult(Set.of(), List.of(), farm.seed(), RegionInvalidReason.TOO_LARGE);
                }

                // Merge with any already-processed newField that overlaps or 6-touches retained.
                List<RegionRecord.RoomRecord> mergedList = new ArrayList<>(newFields.size() + 1);
                Set<Long> combinedSet = new HashSet<>(retained);
                long chosenSeed = retained.contains(oldField.seed()) ? oldField.seed()
                        : (liveSeed != Long.MIN_VALUE ? liveSeed : retained.iterator().next());
                String chosenCrop = oldField.preferredCropId();
                for (RegionRecord.RoomRecord prev : newFields) {
                    if (sharesOrTouches(prev.blocks(), combinedSet)) {
                        LongIterator pit = prev.blocks().iterator();
                        while (pit.hasNext()) {
                            combinedSet.add(pit.nextLong());
                        }
                        if (chosenCrop == null && prev.preferredCropId() != null) {
                            chosenCrop = prev.preferredCropId();
                        }
                    } else {
                        mergedList.add(prev);
                    }
                }
                if (combinedSet.size() > RegionRules.FARM_FIELD_MAX_BLOCKS) {
                    return new RescanResult(Set.of(), List.of(), farm.seed(), RegionInvalidReason.TOO_LARGE);
                }
                mergedList.add(new RegionRecord.RoomRecord(
                        newSheds.size() + mergedList.size(), chosenSeed, combinedSet, EnumSet.of(RoomLabel.FIELD),
                        List.of(), chosenCrop));
                newFields = mergedList;
                coveredFieldBlocks.addAll(combinedSet);
            }
        }

        if (newSheds.isEmpty()) {
            return new RescanResult(Set.of(), List.of(), farm.seed(), RegionInvalidReason.NO_SHED);
        }

        List<RegionRecord.RoomRecord> combined = new ArrayList<>(newSheds.size() + newFields.size());
        for (RegionRecord.RoomRecord s : newSheds) {
            combined.add(new RegionRecord.RoomRecord(
                    combined.size(), s.seed(), s.blocks(), s.labels(), s.storageBlocks(), s.preferredCropId()));
        }
        for (RegionRecord.RoomRecord f : newFields) {
            combined.add(new RegionRecord.RoomRecord(
                    combined.size(), f.seed(), f.blocks(), f.labels(), f.storageBlocks(), f.preferredCropId()));
        }

        Set<Long> allBlocks = new HashSet<>(coveredShedBlocks.size() + coveredFieldBlocks.size());
        LongIterator sit = coveredShedBlocks.iterator();
        while (sit.hasNext()) {
            allBlocks.add(sit.nextLong());
        }
        LongIterator fit = coveredFieldBlocks.iterator();
        while (fit.hasNext()) {
            allBlocks.add(fit.nextLong());
        }

        long newFarmSeed = pickFarmSeed(level, combined, farm.seed());
        return new RescanResult(allBlocks, combined, newFarmSeed, RegionInvalidReason.NONE);
    }

    @Nullable
    private static String findPreservedCrop(List<RegionRecord.RoomRecord> oldFields, Set<Long> filled,
                                            @Nullable String primaryCrop) {
        if (primaryCrop != null) {
            return primaryCrop;
        }
        for (RegionRecord.RoomRecord old : oldFields) {
            if (old.preferredCropId() != null && sharesAnyBlock(old.blocks(), filled)) {
                return old.preferredCropId();
            }
        }
        return null;
    }

    private static boolean sharesOrTouches(LongSet a, Set<Long> b) {
        LongIterator it = a.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (b.contains(key)) {
                return true;
            }
            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);
            for (int i = 0; i < 6; i++) {
                if (b.contains(BlockKeys.pack(x + DX[i], y + DY[i], z + DZ[i]))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Validates a fixed-bound {@link RegionType#FARM} region (e.g. {@link RegionOrigin#MANUAL}): checks that at least
     * one shed room exists, room caps are respected, all chunks remain staked, no overlaps with other regions exist,
     * and no hazards lie inside or 6-adjacent to any block.
     */
    public static RegionInvalidReason validate(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                               RegionRecord farm) {
        int shedCount = 0;
        for (RegionRecord.RoomRecord room : farm.rooms()) {
            if (room.isShed()) {
                shedCount++;
                if (room.blockCount() > RegionRules.FARM_SHED_MAX_BLOCKS) {
                    return RegionInvalidReason.TOO_LARGE;
                }
            } else if (room.isField()) {
                if (room.blockCount() > RegionRules.FARM_FIELD_MAX_BLOCKS) {
                    return RegionInvalidReason.TOO_LARGE;
                }
            }
        }
        if (shedCount == 0) {
            return RegionInvalidReason.NO_SHED;
        }

        LongIterator chunkIt = farm.chunks().iterator();
        while (chunkIt.hasNext()) {
            if (!empire.state().staked().contains(chunkIt.nextLong())) {
                return RegionInvalidReason.UNSTAKED;
            }
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        LongIterator it = farm.blocks().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            RegionRecord existing = data.regionAt(key);
            if (existing != null && !existing.id().equals(farm.id())) {
                return RegionInvalidReason.OVERLAPS_EXISTING;
            }
            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);
            cursor.set(x, y, z);
            if (isHazard(level.getBlockState(cursor))) {
                return RegionInvalidReason.HAZARD;
            }
            for (int i = 0; i < 6; i++) {
                int nx = x + DX[i];
                int ny = y + DY[i];
                int nz = z + DZ[i];
                if (farm.containsBlock(BlockKeys.pack(nx, ny, nz))) {
                    continue;
                }
                neighbor.set(nx, ny, nz);
                if (level.hasChunkAt(neighbor) && isHazard(level.getBlockState(neighbor))) {
                    return RegionInvalidReason.HAZARD;
                }
            }
        }
        return RegionInvalidReason.NONE;
    }

    private static RegionFloodFill.Result fillRoom(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                                   int startX, int startY, int startZ,
                                                   RoomLabel label, @Nullable UUID ignoreRegionId,
                                                   LongSet otherTagBoundary) {
        int maxBlocks = label == RoomLabel.SHED
                ? RegionRules.FARM_SHED_MAX_BLOCKS
                : RegionRules.FARM_FIELD_MAX_BLOCKS;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowCursor = new BlockPos.MutableBlockPos();

        return RegionFloodFill.fill(
                startX, startY, startZ, maxBlocks,
                (x, y, z) -> {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) {
                        return RegionFloodFill.Cell.BOUNDARY;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (isHazard(state)) {
                        return RegionFloodFill.Cell.HAZARD;
                    }
                    long chunk = ChunkKeys.pack(x >> 4, z >> 4);
                    if (!empire.state().staked().contains(chunk)) {
                        return RegionFloodFill.Cell.BOUNDARY;
                    }
                    long key = BlockKeys.pack(x, y, z);
                    RegionRecord existing = data.regionAt(key);
                    if (existing != null && !existing.id().equals(ignoreRegionId)) {
                        return RegionFloodFill.Cell.BOUNDARY;
                    }
                    if (otherTagBoundary.contains(key)) {
                        return RegionFloodFill.Cell.BOUNDARY;
                    }
                    if (label == RoomLabel.SHED) {
                        return isShedBlock(state) ? RegionFloodFill.Cell.INCLUDE : RegionFloodFill.Cell.BOUNDARY;
                    }
                    // FIELD: farmland, crops, or the block directly above any eligible farmland (propagates onward).
                    if (isDirectFieldBlock(state)) {
                        return RegionFloodFill.Cell.INCLUDE;
                    }
                    if (isEligibleFarmland(level, data, empire, x, y - 1, z, ignoreRegionId, otherTagBoundary, belowCursor)) {
                        return RegionFloodFill.Cell.INCLUDE;
                    }
                    return RegionFloodFill.Cell.BOUNDARY;
                });
    }

    private static boolean isEligibleFarmland(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                              int x, int y, int z, @Nullable UUID ignoreRegionId,
                                              LongSet otherTagBoundary, BlockPos.MutableBlockPos scratch) {
        scratch.set(x, y, z);
        if (!level.hasChunkAt(scratch)) {
            return false;
        }
        long chunk = ChunkKeys.pack(x >> 4, z >> 4);
        if (!empire.state().staked().contains(chunk)) {
            return false;
        }
        long key = BlockKeys.pack(x, y, z);
        RegionRecord existing = data.regionAt(key);
        if (existing != null && !existing.id().equals(ignoreRegionId)) {
            return false;
        }
        if (otherTagBoundary.contains(key)) {
            return false;
        }
        BlockState state = level.getBlockState(scratch);
        return !isHazard(state) && isFarmland(state);
    }

    private static long resolveShedSeed(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                        UUID farmId, RegionRecord.RoomRecord oldShed, boolean allowAdjacentRecovery) {
        long currentSeed = oldShed.seed();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
                BlockKeys.getX(currentSeed), BlockKeys.getY(currentSeed), BlockKeys.getZ(currentSeed));
        if (level.hasChunkAt(cursor)) {
            BlockState seedState = level.getBlockState(cursor);
            if (isComposter(seedState) || isShedBlock(seedState)) {
                return currentSeed;
            }
        }
        long composter = Long.MAX_VALUE;
        boolean foundComposter = false;
        long anyShed = Long.MAX_VALUE;
        boolean foundAny = false;
        LongIterator it = oldShed.blocks().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            cursor.set(BlockKeys.getX(key), BlockKeys.getY(key), BlockKeys.getZ(key));
            if (!level.hasChunkAt(cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (isComposter(state)) {
                if (!foundComposter || key < composter) {
                    composter = key;
                    foundComposter = true;
                }
            }
            if (isShedBlock(state)) {
                if (!foundAny || key < anyShed) {
                    anyShed = key;
                    foundAny = true;
                }
            }
        }
        if (foundComposter) {
            return composter;
        }
        if (foundAny) {
            return anyShed;
        }
        if (allowAdjacentRecovery) {
            return findAdjacentComposter(level, data, empire, farmId, List.of(oldShed));
        }
        return Long.MIN_VALUE;
    }

    private static long findAdjacentComposter(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                              UUID farmId, List<RegionRecord.RoomRecord> rooms) {
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        long best = Long.MAX_VALUE;
        boolean found = false;
        for (RegionRecord.RoomRecord room : rooms) {
            LongIterator it = room.blocks().iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                int x = BlockKeys.getX(key);
                int y = BlockKeys.getY(key);
                int z = BlockKeys.getZ(key);
                for (int i = 0; i < 6; i++) {
                    int nx = x + DX[i];
                    int ny = y + DY[i];
                    int nz = z + DZ[i];
                    long nKey = BlockKeys.pack(nx, ny, nz);
                    if (!empire.state().staked().contains(BlockKeys.chunkKey(nKey))) {
                        continue;
                    }
                    RegionRecord existing = data.regionAt(nKey);
                    if (existing != null && !existing.id().equals(farmId)) {
                        continue;
                    }
                    neighbor.set(nx, ny, nz);
                    if (level.hasChunkAt(neighbor) && isComposter(level.getBlockState(neighbor))) {
                        if (!found || nKey < best) {
                            best = nKey;
                            found = true;
                        }
                    }
                }
            }
        }
        return found ? best : Long.MIN_VALUE;
    }

    private static long resolveFieldSeed(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                         UUID farmId, RegionRecord.RoomRecord oldField, LongSet shedBoundary) {
        long currentSeed = oldField.seed();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
                BlockKeys.getX(currentSeed), BlockKeys.getY(currentSeed), BlockKeys.getZ(currentSeed));
        if (!shedBoundary.contains(currentSeed) && level.hasChunkAt(cursor)
                && isDirectFieldBlock(level.getBlockState(cursor))) {
            return currentSeed;
        }
        long directBest = Long.MAX_VALUE;
        boolean foundDirect = false;
        long aboveBest = Long.MAX_VALUE;
        boolean foundAbove = false;
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();

        LongIterator it = oldField.blocks().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (shedBoundary.contains(key)) {
                continue;
            }
            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);
            cursor.set(x, y, z);
            if (!level.hasChunkAt(cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (isDirectFieldBlock(state)) {
                if (!foundDirect || key < directBest) {
                    directBest = key;
                    foundDirect = true;
                }
            } else if (!foundDirect
                    && isEligibleFarmland(level, data, empire, x, y - 1, z, farmId, shedBoundary, below)) {
                if (!foundAbove || key < aboveBest) {
                    aboveBest = key;
                    foundAbove = true;
                }
            }
        }
        if (foundDirect) {
            return directBest;
        }
        return foundAbove ? aboveBest : Long.MIN_VALUE;
    }

    private static LongOpenHashSet collectActiveFieldBlocks(ServerLevel level, List<RegionRecord.RoomRecord> oldFields) {
        LongOpenHashSet active = new LongOpenHashSet();
        if (oldFields.isEmpty()) {
            return active;
        }
        LongOpenHashSet allOldField = new LongOpenHashSet();
        for (RegionRecord.RoomRecord room : oldFields) {
            allOldField.addAll(room.blocks());
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        LongIterator it = allOldField.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);
            cursor.set(x, y, z);
            if (!level.hasChunkAt(cursor)) {
                active.add(key);
                continue;
            }
            if (isDirectFieldBlock(level.getBlockState(cursor))) {
                active.add(key);
                continue;
            }
            long belowKey = BlockKeys.pack(x, y - 1, z);
            if (allOldField.contains(belowKey)) {
                below.set(x, y - 1, z);
                if (level.hasChunkAt(below) && isFarmland(level.getBlockState(below))) {
                    active.add(key);
                }
            }
        }
        return active;
    }

    private static List<Long> collectStorageBlocks(ServerLevel level, Set<Long> roomBlocks) {
        List<Long> storage = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long key : roomBlocks) {
            cursor.set(BlockKeys.getX(key), BlockKeys.getY(key), BlockKeys.getZ(key));
            if (level.hasChunkAt(cursor) && isStorageBlock(level.getBlockState(cursor))) {
                storage.add(key);
            }
        }
        return storage;
    }

    private static LongOpenHashSet blocksOfOtherLabel(RegionRecord farm, RoomLabel label) {
        LongOpenHashSet out = new LongOpenHashSet();
        for (RegionRecord.RoomRecord room : farm.rooms()) {
            if (!room.hasLabel(label)) {
                out.addAll(room.blocks());
            }
        }
        return out;
    }

    private static boolean sharesAnyBlock(LongSet a, Set<Long> b) {
        LongIterator it = a.iterator();
        while (it.hasNext()) {
            if (b.contains(it.nextLong())) {
                return true;
            }
        }
        return false;
    }

    private static Set<Long> unionRoomBlocks(List<RegionRecord.RoomRecord> rooms) {
        Set<Long> all = new HashSet<>();
        for (RegionRecord.RoomRecord room : rooms) {
            LongIterator it = room.blocks().iterator();
            while (it.hasNext()) {
                all.add(it.nextLong());
            }
        }
        return all;
    }

    private static long pickFarmSeed(ServerLevel level, List<RegionRecord.RoomRecord> rooms, long fallback) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        long firstShedSeed = Long.MIN_VALUE;
        for (RegionRecord.RoomRecord room : rooms) {
            if (!room.isShed()) {
                continue;
            }
            if (firstShedSeed == Long.MIN_VALUE) {
                firstShedSeed = room.seed();
            }
            cursor.set(BlockKeys.getX(room.seed()), BlockKeys.getY(room.seed()), BlockKeys.getZ(room.seed()));
            if (level.hasChunkAt(cursor) && isComposter(level.getBlockState(cursor))) {
                return room.seed();
            }
            LongIterator it = room.blocks().iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                cursor.set(BlockKeys.getX(key), BlockKeys.getY(key), BlockKeys.getZ(key));
                if (level.hasChunkAt(cursor) && isComposter(level.getBlockState(cursor))) {
                    return key;
                }
            }
        }
        if (firstShedSeed != Long.MIN_VALUE) {
            return firstShedSeed;
        }
        return rooms.isEmpty() ? fallback : rooms.get(0).seed();
    }

    private FarmRegionScanner() {
    }
}
