// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.region.FarmRegionScanner;
import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.FarmerUtility;
import dev.brights0ng.empire.rules.Occupation;
import dev.brights0ng.empire.rules.RegionType;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Shared short-TTL scanner, field-room grouper, and inventory/storage analyzer for a {@link Occupation#FARMER}'s
 * assigned {@link RegionType#FARM}. Shared across all Farmer Level-3 tasks in the same {@code WORK} slot.
 */
public final class FarmWorkScanner {

    private static final long CACHE_TTL_TICKS = 20L;

    public record MatureCropTarget(BlockPos cropPos, int roomIndex) {
    }

    public record EmptyPlotTarget(BlockPos cropPos, @Nullable Item targetSeed, int roomIndex) {
    }

    public record RevertedSoilTarget(BlockPos soilPos, int roomIndex) {
    }

    public record FarmSnapshot(
            @Nullable RegionRecord farm,
            List<MatureCropTarget> matureCrops,
            List<RevertedSoilTarget> revertedDirt,
            List<EmptyPlotTarget> emptyPlots,
            Map<Item, Integer> missingBySeed,
            Map<Item, Integer> shedSeedCounts,
            List<BlockPos> storageBlocks,
            List<ItemEntity> strayDrops
    ) {
        public static final FarmSnapshot EMPTY = new FarmSnapshot(
                null, List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), List.of());

        public boolean valid() {
            return farm != null && farm.valid();
        }

        public boolean hasFieldWork() {
            return !matureCrops.isEmpty() || !revertedDirt.isEmpty() || !emptyPlots.isEmpty();
        }
    }

    @Nullable
    private FarmSnapshot cached;
    private long cachedGameTime = Long.MIN_VALUE;
    private long cachedRevision = Long.MIN_VALUE;

    /** Set by {@link SeedFieldTask} when it needs seeds from shed storage; cleared by {@link FetchSuppliesTask} or Harvest. */
    private boolean seedFetchRequested;
    /** Set by {@link HarvestFieldTask} when a crop drop overflows onto the ground; cleared when pockets free up or dump completes. */
    private boolean inventoryOverflowed;

    public void invalidate() {
        cached = null;
        cachedGameTime = Long.MIN_VALUE;
        cachedRevision = Long.MIN_VALUE;
    }

    public boolean isSeedFetchRequested() {
        return seedFetchRequested;
    }

    public void requestSeedFetch() {
        seedFetchRequested = true;
    }

    public void clearSeedFetchRequest() {
        seedFetchRequested = false;
    }

    public boolean isInventoryOverflowed(CivilianEntity civ) {
        if (inventoryOverflowed && hasEmptyPocketSlot(civ)) {
            inventoryOverflowed = false;
        }
        return inventoryOverflowed;
    }

    public void flagInventoryOverflow() {
        inventoryOverflowed = true;
    }

    public void clearInventoryOverflow() {
        inventoryOverflowed = false;
    }

    public FarmSnapshot scan(AiContext ctx) {
        WorkAssignment work = ctx.civilian().workAssignment();
        EmpireRecord empire = ctx.empire();
        if (work == null || !work.isOccupation() || work.occupation() != Occupation.FARMER
                || work.workplaceRegionId() == null || empire == null) {
            return FarmSnapshot.EMPTY;
        }
        RegionRecord farm = ctx.data().region(work.workplaceRegionId());
        if (farm == null || !farm.valid() || farm.type() != RegionType.FARM
                || !farm.empireId().equals(empire.id())) {
            return FarmSnapshot.EMPTY;
        }
        long now = ctx.level().getGameTime();
        if (cached != null && cached.farm() == farm
                && cachedRevision == farm.revision()
                && (now - cachedGameTime) < CACHE_TTL_TICKS) {
            return cached;
        }
        cached = buildSnapshot(ctx.level(), ctx.civilian(), farm);
        cachedGameTime = now;
        cachedRevision = farm.revision();
        return cached;
    }

    private static FarmSnapshot buildSnapshot(ServerLevel level, CivilianEntity civ, RegionRecord farm) {
        List<BlockPos> storageBlocks = new ArrayList<>();
        Map<Item, Integer> shedSeedCounts = new LinkedHashMap<>();
        LongIterator sit = farm.storageBlocks().iterator();
        while (sit.hasNext()) {
            BlockPos pos = BlockPos.of(sit.nextLong());
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) {
                continue;
            }
            storageBlocks.add(pos);
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack s = handler.getStackInSlot(slot);
                if (FarmCrops.isPlantableSeed(s)) {
                    shedSeedCounts.merge(s.getItem(), s.getCount(), Integer::sum);
                }
            }
        }

        Item fallbackSeed = firstAvailableSeed(civ, shedSeedCounts);
        List<MatureCropTarget> matureCrops = new ArrayList<>();
        List<RevertedSoilTarget> revertedDirt = new ArrayList<>();
        List<EmptyPlotTarget> emptyPlots = new ArrayList<>();
        Map<Item, Integer> missingBySeed = new LinkedHashMap<>();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();

        int fieldMinX = Integer.MAX_VALUE, fieldMinY = Integer.MAX_VALUE, fieldMinZ = Integer.MAX_VALUE;
        int fieldMaxX = Integer.MIN_VALUE, fieldMaxY = Integer.MIN_VALUE, fieldMaxZ = Integer.MIN_VALUE;
        boolean hasFields = false;

        List<RegionRecord.RoomRecord> rooms = farm.rooms();
        for (int roomIndex = 0; roomIndex < rooms.size(); roomIndex++) {
            RegionRecord.RoomRecord room = rooms.get(roomIndex);
            if (!room.isField()) {
                continue;
            }
            hasFields = true;
            Item roomTargetSeed = resolveTargetSeedForRoom(level, room, fallbackSeed);

            LongIterator it = room.blocks().iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                int x = BlockKeys.getX(key);
                int y = BlockKeys.getY(key);
                int z = BlockKeys.getZ(key);
                if (x < fieldMinX) fieldMinX = x;
                if (y < fieldMinY) fieldMinY = y;
                if (z < fieldMinZ) fieldMinZ = z;
                if (x > fieldMaxX) fieldMaxX = x;
                if (y > fieldMaxY) fieldMaxY = y;
                if (z > fieldMaxZ) fieldMaxZ = z;

                cursor.set(x, y, z);
                if (!level.hasChunkAt(cursor)) {
                    continue;
                }
                BlockState state = level.getBlockState(cursor);

                if (FarmCrops.isMatureCrop(state)) {
                    matureCrops.add(new MatureCropTarget(cursor.immutable(), roomIndex));
                } else if (FarmRegionScanner.isFarmland(state)) {
                    above.set(x, y + 1, z);
                    if (level.hasChunkAt(above) && level.getBlockState(above).isAir()) {
                        emptyPlots.add(new EmptyPlotTarget(above.immutable(), roomTargetSeed, roomIndex));
                        if (roomTargetSeed != null) {
                            missingBySeed.merge(roomTargetSeed, 1, Integer::sum);
                        }
                    }
                } else if (FarmCrops.isTillableSoil(level, cursor)) {
                    // Never treat a block sitting above farmland as a soil layer to till.
                    below.set(x, y - 1, z);
                    if (!level.hasChunkAt(below) || !FarmRegionScanner.isFarmland(level.getBlockState(below))) {
                        revertedDirt.add(new RevertedSoilTarget(cursor.immutable(), roomIndex));
                        if (roomTargetSeed != null) {
                            missingBySeed.merge(roomTargetSeed, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        List<ItemEntity> strayDrops = new ArrayList<>();
        Set<Integer> seenDropIds = new HashSet<>();
        if (hasFields) {
            AABB fieldBox = new AABB(fieldMinX - 1.5, fieldMinY - 1.0, fieldMinZ - 1.5,
                    fieldMaxX + 2.5, fieldMaxY + 2.5, fieldMaxZ + 2.5);
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, fieldBox)) {
                if (item.isAlive() && !item.getItem().isEmpty()
                        && FarmCrops.isFarmProduceOrSeed(item.getItem())
                        && seenDropIds.add(item.getId())) {
                    strayDrops.add(item);
                }
            }
        }
        // Also collect produce/seed spills dropped near shed storage blocks (e.g. rookie Logistics fumbles).
        for (BlockPos storagePos : storageBlocks) {
            AABB shedBox = new AABB(storagePos).inflate(2.5, 1.5, 2.5);
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, shedBox)) {
                if (item.isAlive() && !item.getItem().isEmpty()
                        && FarmCrops.isFarmProduceOrSeed(item.getItem())
                        && seenDropIds.add(item.getId())) {
                    strayDrops.add(item);
                }
            }
        }

        return new FarmSnapshot(farm, matureCrops, revertedDirt, emptyPlots, missingBySeed,
                shedSeedCounts, storageBlocks, strayDrops);
    }

    /**
     * Resolves what seed a {@code FIELD} room should plant:
     * (1) explicit player override {@link RegionRecord.RoomRecord#preferredCropId()},
     * (2) majority {@code CropBlock} currently growing in the room,
     * (3) {@code fallbackSeed} (first available seed in pockets or shed storage for fresh fields).
     */
    @Nullable
    public static Item resolveTargetSeedForRoom(ServerLevel level, RegionRecord.RoomRecord room,
                                                @Nullable Item fallbackSeed) {
        if (room.preferredCropId() != null) {
            Item overridden = FarmCrops.seedItemById(room.preferredCropId());
            if (overridden != null) {
                return overridden;
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongIterator it = room.blocks().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            cursor.set(BlockKeys.getX(key), BlockKeys.getY(key), BlockKeys.getZ(key));
            if (!level.hasChunkAt(cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (FarmCrops.isBasicCrop(state)) {
                String seedId = FarmCrops.seedIdForCrop(level, cursor, state);
                if (seedId != null) {
                    counts.merge(seedId, 1, Integer::sum);
                }
            }
        }
        String majority = FarmerUtility.majorityKey(counts);
        if (majority != null) {
            Item majItem = FarmCrops.seedItemById(majority);
            if (majItem != null) {
                return majItem;
            }
        }
        return fallbackSeed;
    }

    @Nullable
    private static Item firstAvailableSeed(CivilianEntity civ, Map<Item, Integer> shedSeedCounts) {
        ItemStack main = civ.getMainHandItem();
        if (FarmCrops.isPlantableSeed(main)) {
            return main.getItem();
        }
        SimpleContainer inv = civ.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (FarmCrops.isPlantableSeed(s)) {
                return s.getItem();
            }
        }
        for (Map.Entry<Item, Integer> e : shedSeedCounts.entrySet()) {
            if (e.getValue() > 0) {
                return e.getKey();
            }
        }
        return null;
    }

    // ---- Shared Inventory, Seed Availability & Storage Helpers ----

    public static boolean hasHoe(CivilianEntity civ) {
        if (FarmCrops.isHoe(civ.getMainHandItem())) {
            return true;
        }
        SimpleContainer inv = civ.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (FarmCrops.isHoe(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    public static int countCarried(CivilianEntity civ, Item item) {
        int total = 0;
        if (civ.getMainHandItem().is(item)) {
            total += civ.getMainHandItem().getCount();
        }
        SimpleContainer inv = civ.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                total += s.getCount();
            }
        }
        return total;
    }

    /**
     * Counts how many empty farmland plots can actually be seeded using seeds carried in pockets or stored in the shed
     * (capped per seed type by total reachable seed count, and excluding shed-only seeds when pockets are completely
     * full with nothing dumpable to free space).
     */
    public static int countSeedableEmptyPlots(ServerLevel level, CivilianEntity civ, FarmSnapshot snap) {
        if (snap.emptyPlots().isEmpty()) {
            return 0;
        }
        Map<Item, Integer> emptyBySeed = new LinkedHashMap<>();
        for (EmptyPlotTarget plot : snap.emptyPlots()) {
            if (plot.targetSeed() != null) {
                emptyBySeed.merge(plot.targetSeed(), 1, Integer::sum);
            }
        }
        boolean canDumpForSpace = false;
        boolean checkedDump = false;
        int total = 0;
        for (Map.Entry<Item, Integer> e : emptyBySeed.entrySet()) {
            Item seed = e.getKey();
            int plots = e.getValue();
            int carried = countCarried(civ, seed);
            int inShed = snap.shedSeedCounts().getOrDefault(seed, 0);
            int reachableFromShed = 0;
            if (inShed > 0) {
                if (canFitAny(civ, new ItemStack(seed))) {
                    reachableFromShed = inShed;
                } else {
                    if (!checkedDump) {
                        checkedDump = true;
                        Map<Item, Integer> dumpable = computeDumpableCounts(civ, snap, false);
                        canDumpForSpace = findDepositStorage(level, civ, snap, dumpable) != null;
                    }
                    if (canDumpForSpace) {
                        reachableFromShed = inShed;
                    }
                }
            }
            total += Math.min(plots, carried + reachableFromShed);
        }
        return total;
    }

    /** True if {@code seed} can be used for seeding (either carried or reachable from shed storage). */
    public static boolean isSeedReachable(ServerLevel level, CivilianEntity civ, FarmSnapshot snap, @Nullable Item seed) {
        if (seed == null) {
            return false;
        }
        if (countCarried(civ, seed) > 0) {
            return true;
        }
        if (snap.shedSeedCounts().getOrDefault(seed, 0) <= 0) {
            return false;
        }
        if (canFitAny(civ, new ItemStack(seed))) {
            return true;
        }
        Map<Item, Integer> dumpable = computeDumpableCounts(civ, snap, false);
        return findDepositStorage(level, civ, snap, dumpable) != null;
    }

    /** True if any stray crop/seed drop on the ground cannot fit into pockets right now. */
    public static boolean hasUnpickableStrayDrops(CivilianEntity civ, FarmSnapshot snap) {
        for (ItemEntity item : snap.strayDrops()) {
            if (item.isAlive() && !item.getItem().isEmpty() && !canFitAny(civ, item.getItem())) {
                return true;
            }
        }
        return false;
    }

    /** True if pockets can fit at least one stack/item of any currently needed seed that exists in shed storage. */
    public static boolean canFitAnyNeededSeed(CivilianEntity civ, FarmSnapshot snap) {
        Map<Item, Integer> needed = computeMissingSeedsToFetch(civ, snap);
        for (Item seed : needed.keySet()) {
            if (snap.shedSeedCounts().getOrDefault(seed, 0) > 0 && canFitAny(civ, new ItemStack(seed))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pocket fullness in {@code [0, 1]} for dump urgency: returns {@code 1.0} as soon as every pocket slot is occupied.
     */
    public static double pocketFillFraction(CivilianEntity civ) {
        SimpleContainer inv = civ.inventory();
        int size = inv.getContainerSize();
        if (size <= 0) {
            return 1.0;
        }
        int emptySlots = 0;
        double usedWeight = 0.0;
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                emptySlots++;
            } else {
                usedWeight += 0.5 + 0.5 * ((double) s.getCount() / Math.max(1, s.getMaxStackSize()));
            }
        }
        if (emptySlots == 0) {
            return 1.0;
        }
        return Math.min(0.99, usedWeight / size);
    }

    /** True if {@code civ} has at least one empty pocket slot. */
    public static boolean hasEmptyPocketSlot(CivilianEntity civ) {
        SimpleContainer inv = civ.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code civ} can store at least 1 more item of {@code sample} in pockets. */
    public static boolean canFitAny(CivilianEntity civ, ItemStack sample) {
        return TransferItemsAction.availableSpaceFor(civ.inventory(), sample) > 0;
    }

    /** Desired seed reserve count for {@code item}: covers missing field spots up to 1 stack max; 0 for non-seeds. */
    public static int desiredKeepCount(FarmSnapshot snap, Item item) {
        if (FarmCrops.cropBlockForSeed(item) == null) {
            return 0;
        }
        int missing = snap.missingBySeed().getOrDefault(item, 0);
        return FarmerUtility.desiredSeedKeepCount(missing, item.getDefaultMaxStackSize());
    }

    /**
     * Computes how many items of each item type {@code civ} should deposit right now:
     * - When {@code endOfShift} is true: ALL farm work items (hoes, seeds, raw produce).
     * - During shift: all farm produce/seeds in excess of {@link #desiredKeepCount(FarmSnapshot, Item)}.
     */
    public static Map<Item, Integer> computeDumpableCounts(CivilianEntity civ, FarmSnapshot snap, boolean endOfShift) {
        Map<Item, Integer> carried = new LinkedHashMap<>();
        ItemStack main = civ.getMainHandItem();
        if (!main.isEmpty() && !civ.isUsingItem()) {
            boolean eligible = endOfShift ? FarmCrops.isFarmWorkItem(main) : FarmCrops.isFarmProduceOrSeed(main);
            if (eligible) {
                carried.merge(main.getItem(), main.getCount(), Integer::sum);
            }
        }
        SimpleContainer inv = civ.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            boolean eligible = endOfShift ? FarmCrops.isFarmWorkItem(s) : FarmCrops.isFarmProduceOrSeed(s);
            if (eligible) {
                carried.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        Map<Item, Integer> dumpable = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : carried.entrySet()) {
            int keep = endOfShift ? 0 : desiredKeepCount(snap, e.getKey());
            int excess = e.getValue() - keep;
            if (excess > 0) {
                dumpable.put(e.getKey(), excess);
            }
        }
        return dumpable;
    }

    /** Computes seeds the farmer is missing compared to {@link #desiredKeepCount(FarmSnapshot, Item)}. */
    public static Map<Item, Integer> computeMissingSeedsToFetch(CivilianEntity civ, FarmSnapshot snap) {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : snap.missingBySeed().entrySet()) {
            Item seed = e.getKey();
            int desired = FarmerUtility.desiredSeedKeepCount(e.getValue(), seed.getDefaultMaxStackSize());
            int carried = countCarried(civ, seed);
            if (desired > carried) {
                needed.put(seed, desired - carried);
            }
        }
        return needed;
    }

    /** Finds the closest shed storage block that can accept at least 1 item from {@code dumpable}. */
    @Nullable
    public static BlockPos findDepositStorage(ServerLevel level, CivilianEntity civ, FarmSnapshot snap,
                                              Map<Item, Integer> dumpable) {
        if (dumpable.isEmpty() || snap.storageBlocks().isEmpty()) {
            return null;
        }
        BlockPos civPos = civ.blockPosition();
        List<BlockPos> sorted = new ArrayList<>(snap.storageBlocks());
        sorted.sort(Comparator.comparingDouble(p -> p.distSqr(civPos)));
        for (BlockPos pos : sorted) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) {
                continue;
            }
            for (Item item : dumpable.keySet()) {
                if (TransferItemsAction.canInsertAny(handler, new ItemStack(item))) {
                    return pos;
                }
            }
        }
        return null;
    }

    /** Finds the closest shed storage block holding a needed hoe or needed seed that fits into pockets. */
    @Nullable
    public static BlockPos findWithdrawStorage(ServerLevel level, CivilianEntity civ, FarmSnapshot snap,
                                               boolean needHoe, Map<Item, Integer> neededSeeds) {
        if ((!needHoe && neededSeeds.isEmpty()) || snap.storageBlocks().isEmpty()) {
            return null;
        }
        BlockPos civPos = civ.blockPosition();
        List<BlockPos> sorted = new ArrayList<>(snap.storageBlocks());
        sorted.sort(Comparator.comparingDouble(p -> p.distSqr(civPos)));
        for (BlockPos pos : sorted) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack s = handler.getStackInSlot(slot);
                if (s.isEmpty()) {
                    continue;
                }
                if (needHoe && FarmCrops.isHoe(s) && canFitAny(civ, s)) {
                    return pos;
                }
                if (neededSeeds.containsKey(s.getItem()) && canFitAny(civ, s)) {
                    return pos;
                }
            }
        }
        return null;
    }

    /**
     * Picks the nearest standable block adjacent to {@code target} (on staked land) for approaching a solid block
     * such as a chest or barrel, falling back to {@code target} itself.
     */
    public static BlockPos approachPos(ServerLevel level, @Nullable EmpireRecord empire, BlockPos target, BlockPos from) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cand = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int nx = target.getX() + dir.getStepX();
            int nz = target.getZ() + dir.getStepZ();
            if (empire != null && !empire.state().staked().contains(ChunkKeys.pack(nx >> 4, nz >> 4))) {
                continue;
            }
            for (int dy : new int[] {0, -1, 1}) {
                cand.set(nx, target.getY() + dy, nz);
                if (level.hasChunkAt(cand) && CivilianBeds.isStandable(level, cand)) {
                    double d2 = cand.distSqr(from);
                    if (best == null || d2 < bestDistSq) {
                        best = cand.immutable();
                        bestDistSq = d2;
                    }
                }
            }
        }
        return best != null ? best : target;
    }

    /** Maximum distance from a target block within which the farmer can act without moving closer. */
    public static double workReach(CivilianEntity civ) {
        return Math.max(2.0, civ.derivedStats().blockReach() - 0.5);
    }
}
