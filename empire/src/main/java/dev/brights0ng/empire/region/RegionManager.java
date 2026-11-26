// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianOccupations;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.ledger.LedgerSelection;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Event-driven region updater (H3 + W3):
 * <ul>
 *   <li>Triggered on Overworld chunk block changes ({@code LevelChunkRegionMixin}) within a region's buffered bounding
 *       box ({@code bounds.inflate(1)}), chunk unstaking, and explicit interaction prods (e.g. civilian bed use,
 *       Ledger).</li>
 *   <li>Leading-edge + 1-second ({@value #UPDATE_COOLDOWN_TICKS}-tick) cooldown throttle: the first prod runs on the
 *       next tick (allowing multi-block edits and lighting to settle), and any prod during the cooldown schedules one
 *       trailing update as soon as the cooldown ends.</li>
 *   <li>{@code AUTO} {@code HOME} regions re-flood-fill from their seed (migrating to another bed in the region if the
 *       original seed is no longer a bed, else staying fixed), updating shape and reconciling cut-off/excess
 *       inhabitants; {@code MANUAL} regions never reshape.</li>
 *   <li>Invalidated {@code AUTO} regions schedule a one-shot 3-day expiry trigger (no periodic sweep).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class RegionManager {

    /** Cooldown in ticks (1 second) between successive updates of the same region. */
    public static final long UPDATE_COOLDOWN_TICKS = 20L;
    /** Delay in ticks for an unthrottled prod so multi-block edits and lighting settle first. */
    public static final long IMMEDIATE_DEFER_TICKS = 1L;

    /** Region id -> earliest Overworld gameTime at which its queued update should run. */
    private static final Map<UUID, Long> SCHEDULED_UPDATES = new HashMap<>();
    /** Invalid AUTO region id -> Overworld gameTime at which its 3-day expiry trigger fires. */
    private static final Map<UUID, Long> SCHEDULED_EXPIRIES = new HashMap<>();
    /** Packed chunk key -> packed BlockPos keys of beds that failed to form a HOME (cleared on 3x3 chunk edit or dawn). */
    private static final Map<Long, LongSet> FAILED_BEDS_BY_CHUNK = new HashMap<>();
    /** DawnClock day index for which {@link #FAILED_BEDS_BY_CHUNK} entries are valid. */
    private static long failedBedsDay = Long.MIN_VALUE;

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        SCHEDULED_UPDATES.clear();
        SCHEDULED_EXPIRIES.clear();
        FAILED_BEDS_BY_CHUNK.clear();
        failedBedsDay = Long.MIN_VALUE;
        EmpireSavedData data = EmpireSavedData.of(event.getServer().overworld());
        for (RegionRecord region : data.allRegions()) {
            scheduleExpiryIfNeeded(region);
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        SCHEDULED_UPDATES.clear();
        SCHEDULED_EXPIRIES.clear();
        FAILED_BEDS_BY_CHUNK.clear();
        failedBedsDay = Long.MIN_VALUE;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        if (!FAILED_BEDS_BY_CHUNK.isEmpty()) {
            syncFailedBedsDay(overworld);
        }
        EmpireSavedData data = EmpireSavedData.of(overworld);

        for (UUID dirtyId : data.drainPendingDirtyRegions()) {
            RegionRecord region = data.region(dirtyId);
            if (region != null) {
                prodRegion(overworld, region);
            }
        }

        if (SCHEDULED_UPDATES.isEmpty() && SCHEDULED_EXPIRIES.isEmpty()) {
            return;
        }

        long now = overworld.getGameTime();

        if (!SCHEDULED_UPDATES.isEmpty()) {
            List<UUID> dueUpdates = null;
            for (Map.Entry<UUID, Long> entry : SCHEDULED_UPDATES.entrySet()) {
                if (now >= entry.getValue()) {
                    if (dueUpdates == null) {
                        dueUpdates = new ArrayList<>();
                    }
                    dueUpdates.add(entry.getKey());
                }
            }
            if (dueUpdates != null) {
                for (UUID id : dueUpdates) {
                    SCHEDULED_UPDATES.remove(id);
                    RegionRecord region = data.region(id);
                    if (region != null) {
                        updateRegion(overworld, data, region, now);
                    }
                }
            }
        }

        if (!SCHEDULED_EXPIRIES.isEmpty()) {
            List<UUID> dueExpiries = null;
            for (Map.Entry<UUID, Long> entry : SCHEDULED_EXPIRIES.entrySet()) {
                if (now >= entry.getValue()) {
                    if (dueExpiries == null) {
                        dueExpiries = new ArrayList<>();
                    }
                    dueExpiries.add(entry.getKey());
                }
            }
            if (dueExpiries != null) {
                for (UUID id : dueExpiries) {
                    SCHEDULED_EXPIRIES.remove(id);
                    RegionRecord region = data.region(id);
                    if (region != null && RegionRules.shouldAutoDelete(
                            region.origin(), region.valid(), region.invalidSinceGameTime(), now)) {
                        detachAllInhabitants(overworld, data, region);
                        CivilianOccupations.detachAllWorkers(overworld, data, region);
                        data.removeRegion(region.id());
                        LedgerSelection.clearForRegion(overworld, region.id());
                        RegionNetwork.pushToEmpire(overworld, region.empireId());
                    }
                }
            }
        }
    }

    /**
     * Called from {@code LevelChunkRegionMixin} whenever any block changes in a loaded Overworld chunk.
     * Clears failed-bed marks in the 3x3 chunk neighborhood and prods every region whose buffered bounding box
     * ({@code bounds.inflate(1)}) contains {@code pos}.
     */
    public static void onBlockChanged(ServerLevel overworld, BlockPos pos) {
        clearFailedBedsNear(pos);
        EmpireSavedData data = EmpireSavedData.of(overworld);
        if (data.allRegions().isEmpty()) {
            return;
        }
        for (RegionRecord region : data.allRegions()) {
            if (region.isWithinInflatedBounds(pos, 1)) {
                region.bumpRevision();
                prodRegion(overworld, region);
            }
        }
    }

    /** Marks a homeless bed head as having failed HOME creation so other civilians skip it until chunk update or dawn. */
    public static void markFailedBed(ServerLevel overworld, BlockPos bedPos) {
        syncFailedBedsDay(overworld);
        long chunk = ChunkKeys.pack(bedPos.getX() >> 4, bedPos.getZ() >> 4);
        FAILED_BEDS_BY_CHUNK.computeIfAbsent(chunk, k -> new LongOpenHashSet()).add(bedPos.asLong());
    }

    /** True if {@code bedPos} is currently marked as having failed HOME creation since the last chunk update or dawn. */
    public static boolean isFailedBed(ServerLevel overworld, BlockPos bedPos) {
        if (FAILED_BEDS_BY_CHUNK.isEmpty()) {
            return false;
        }
        syncFailedBedsDay(overworld);
        LongSet set = FAILED_BEDS_BY_CHUNK.get(ChunkKeys.pack(bedPos.getX() >> 4, bedPos.getZ() >> 4));
        return set != null && set.contains(bedPos.asLong());
    }

    /** Clears failed-bed marks in the 3x3 chunk neighborhood around {@code pos}. */
    public static void clearFailedBedsNear(BlockPos pos) {
        if (FAILED_BEDS_BY_CHUNK.isEmpty()) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                FAILED_BEDS_BY_CHUNK.remove(ChunkKeys.pack(cx + dx, cz + dz));
            }
        }
    }

    private static void syncFailedBedsDay(ServerLevel overworld) {
        long day = DawnClock.dayOf(overworld.getDayTime());
        if (day != failedBedsDay) {
            FAILED_BEDS_BY_CHUNK.clear();
            failedBedsDay = day;
        }
    }

    /**
     * Prods a single region to update. Runs on the next tick if outside its 1-second cooldown; if prodded during the
     * cooldown, schedules one trailing update for when the cooldown ends so changes are never dropped.
     */
    public static void prodRegion(ServerLevel overworld, RegionRecord region) {
        long now = overworld.getGameTime();
        region.setDirtyFlag(true);
        long last = region.lastUpdateGameTime();
        long runAt = (last == Long.MIN_VALUE || now < last || now - last >= UPDATE_COOLDOWN_TICKS)
                ? (now + IMMEDIATE_DEFER_TICKS)
                : (last + UPDATE_COOLDOWN_TICKS);
        SCHEDULED_UPDATES.merge(region.id(), runAt, Math::min);
    }

    /** Forces an immediate update of all Overworld regions (used by {@code /empire debug region validate}). */
    public static void updateAllNow(ServerLevel overworld) {
        EmpireSavedData data = EmpireSavedData.of(overworld);
        long gameTime = overworld.getGameTime();
        for (RegionRecord region : List.copyOf(data.allRegions())) {
            updateRegion(overworld, data, region, gameTime);
        }
    }

    /** Alias for {@link #updateRegion(ServerLevel, EmpireSavedData, RegionRecord, long)}. */
    public static void validateRegion(ServerLevel overworld, EmpireSavedData data, RegionRecord region, long gameTime) {
        updateRegion(overworld, data, region, gameTime);
    }

    /**
     * Updates a single region if its chunks are loaded:
     * <ul>
     *   <li>{@code AUTO} {@code HOME} regions re-flood-fill from their seed (migrating to another bed in the region if
     *       needed), reshape in place on success, reconcile cut-off/excess inhabitants, and keep their old bounds on
     *       invalidation so repairs still trigger recovery.</li>
     *   <li>{@code MANUAL} regions never reshape and run standard validation on their fixed bounds.</li>
     * </ul>
     */
    public static void updateRegion(ServerLevel overworld, EmpireSavedData data, RegionRecord region, long gameTime) {
        SCHEDULED_UPDATES.remove(region.id());
        region.setLastUpdateGameTime(gameTime);

        EmpireRecord empire = data.getEmpire(region.empireId());
        if (empire == null) {
            detachAllInhabitants(overworld, data, region);
            CivilianOccupations.detachAllWorkers(overworld, data, region);
            SCHEDULED_EXPIRIES.remove(region.id());
            data.removeRegion(region.id());
            LedgerSelection.clearForRegion(overworld, region.id());
            return;
        }

        if (HomeRegionScanner.areChunksLoaded(overworld, region)) {
            region.setDirtyFlag(false);
            if (region.type() == RegionType.HOME && region.origin() == RegionOrigin.AUTO) {
                updateAutoHome(overworld, data, empire, region, gameTime);
            } else if (region.type() == RegionType.FARM && region.origin() == RegionOrigin.AUTO) {
                updateAutoFarm(overworld, data, empire, region, gameTime);
            } else {
                updateFixedRegion(overworld, data, empire, region, gameTime);
            }
        }

        if (RegionRules.shouldAutoDelete(region.origin(), region.valid(), region.invalidSinceGameTime(), gameTime)) {
            detachAllInhabitants(overworld, data, region);
            CivilianOccupations.detachAllWorkers(overworld, data, region);
            SCHEDULED_EXPIRIES.remove(region.id());
            data.removeRegion(region.id());
            LedgerSelection.clearForRegion(overworld, region.id());
            RegionNetwork.pushToEmpire(overworld, region.empireId());
        }
    }

    private static void updateAutoHome(ServerLevel overworld, EmpireSavedData data, EmpireRecord empire,
                                       RegionRecord region, long gameTime) {
        HomeRegionScanner.RescanResult rescan = HomeRegionScanner.rescan(overworld, data, empire, region);
        if (rescan.seed() != region.seed()) {
            region.setSeed(rescan.seed());
            data.setDirty();
        }
        if (rescan.succeeded()) {
            boolean shapeChanged = !region.blocks().equals(rescan.blocks());
            if (shapeChanged) {
                data.replaceRegionBlocks(region, rescan.blocks());
            }
            boolean roomsChanged = region.setRooms(rescan.partition());
            if (roomsChanged) {
                data.setDirty();
            }
            boolean becameValid = !region.valid();
            if (becameValid) {
                region.markValid();
                SCHEDULED_EXPIRIES.remove(region.id());
                data.setDirty();
            }
            reconcileInhabitants(overworld, data, region);
            if (shapeChanged || roomsChanged || becameValid) {
                region.bumpRevision();
                RegionNetwork.pushToEmpire(overworld, region.empireId());
            }
        } else {
            applyInvalid(overworld, data, region, rescan.failure(), gameTime);
        }
    }

    private static void updateAutoFarm(ServerLevel overworld, EmpireSavedData data, EmpireRecord empire,
                                       RegionRecord region, long gameTime) {
        FarmRegionScanner.RescanResult rescan = FarmRegionScanner.rescan(overworld, data, empire, region);
        if (rescan.seed() != region.seed()) {
            region.setSeed(rescan.seed());
            data.setDirty();
        }
        if (rescan.succeeded()) {
            boolean shapeChanged = !region.blocks().equals(rescan.blocks());
            if (shapeChanged) {
                data.replaceRegionBlocks(region, rescan.blocks());
            }
            boolean roomsChanged = region.setRoomRecords(rescan.rooms(), java.util.Set.of());
            if (roomsChanged) {
                data.setDirty();
            }
            boolean becameValid = !region.valid();
            if (becameValid) {
                region.markValid();
                SCHEDULED_EXPIRIES.remove(region.id());
                data.setDirty();
                CivilianOccupations.onWorkplaceValidityChanged(overworld, data, region);
            }
            if (shapeChanged || roomsChanged || becameValid) {
                region.bumpRevision();
                RegionNetwork.pushToEmpire(overworld, region.empireId());
            }
        } else {
            applyInvalid(overworld, data, region, rescan.failure(), gameTime);
        }
    }

    private static void updateFixedRegion(ServerLevel overworld, EmpireSavedData data, EmpireRecord empire,
                                          RegionRecord region, long gameTime) {
        boolean roomsChanged = region.type() == RegionType.HOME
                && HomeRegionScanner.refreshRooms(overworld, region);
        RegionInvalidReason reason = switch (region.type()) {
            case HOME -> HomeRegionScanner.validate(overworld, data, empire, region);
            case FARM -> FarmRegionScanner.validate(overworld, data, empire, region);
        };
        if (reason == RegionInvalidReason.NONE) {
            boolean becameValid = !region.valid();
            if (becameValid) {
                region.markValid();
                SCHEDULED_EXPIRIES.remove(region.id());
                if (!region.workers().isEmpty()) {
                    CivilianOccupations.onWorkplaceValidityChanged(overworld, data, region);
                }
            }
            if (roomsChanged) {
                reconcileInhabitants(overworld, data, region);
            }
            if (becameValid || roomsChanged) {
                region.bumpRevision();
                data.setDirty();
                RegionNetwork.pushToEmpire(overworld, region.empireId());
            }
        } else {
            applyInvalid(overworld, data, region, reason, gameTime);
        }
    }

    private static void applyInvalid(ServerLevel overworld, EmpireSavedData data, RegionRecord region,
                                     RegionInvalidReason reason, long gameTime) {
        boolean wasValid = region.valid();
        RegionInvalidReason prevReason = region.invalidReason();
        region.markInvalid(reason, gameTime);
        scheduleExpiryIfNeeded(region);
        if (wasValid || prevReason != reason) {
            region.bumpRevision();
            data.setDirty();
        }
        if (wasValid) {
            RegionNetwork.pushToEmpire(overworld, region.empireId());
        }
        if (wasValid || !region.inhabitants().isEmpty()) {
            detachAllInhabitants(overworld, data, region);
        }
        // Workplace regions pause workers while INVALID rather than unassigning them (Bright, 2026-11-25).
        if (wasValid && !region.workers().isEmpty()) {
            CivilianOccupations.onWorkplaceValidityChanged(overworld, data, region);
        }
    }

    private static void scheduleExpiryIfNeeded(RegionRecord region) {
        if (region.origin() == RegionOrigin.AUTO && !region.valid() && region.invalidSinceGameTime() >= 0L) {
            SCHEDULED_EXPIRIES.put(region.id(), region.invalidSinceGameTime() + RegionRules.INVALID_AUTO_DELETE_TICKS);
        } else {
            SCHEDULED_EXPIRIES.remove(region.id());
        }
    }

    /**
     * After a {@code HOME} reshapes or its rooms change, detaches any inhabitant whose claimed bed was cut off or
     * turned outdoor, then evicts excess inhabitants (most recently added first) if effective indoor volume shrank.
     */
    private static void reconcileInhabitants(ServerLevel overworld, EmpireSavedData data, RegionRecord region) {
        if (region.inhabitants().isEmpty()) {
            return;
        }
        boolean changed = false;
        // 1. Detach inhabitants whose claimed bed is missing, cut off, or now in an OUTDOOR room / separator.
        for (UUID civilianId : List.copyOf(region.inhabitants())) {
            CivilianRecord record = data.civilian(civilianId);
            BlockPos bed = record != null ? record.bed() : null;
            if (bed == null && record != null && overworld.getEntity(record.body()) instanceof CivilianEntity body) {
                bed = body.claimedBed();
            }
            if (bed == null || !region.containsBlock(bed) || !region.isIndoorBlock(bed)) {
                detachSingleInhabitant(overworld, data, region, civilianId);
                changed = true;
            }
        }
        // 2. If capacity shrank below remaining inhabitant count, evict excess inhabitants (newest first).
        int capacity = RegionRules.homeCapacity(region.effectiveVolume());
        if (region.inhabitants().size() > capacity) {
            List<UUID> ordered = new ArrayList<>(region.inhabitants());
            for (int i = ordered.size() - 1; i >= capacity; i--) {
                detachSingleInhabitant(overworld, data, region, ordered.get(i));
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
        }
    }

    private static void detachSingleInhabitant(ServerLevel overworld, EmpireSavedData data, RegionRecord region,
                                               UUID civilianId) {
        CivilianRecord record = data.civilian(civilianId);
        if (record != null) {
            if (record.bed() != null) {
                CivilianBeds.release(overworld, record.bed());
                record.setBed(null);
            }
            if (overworld.getEntity(record.body()) instanceof CivilianEntity body) {
                if (body.claimedBed() != null) {
                    CivilianBeds.release(overworld, body.claimedBed());
                    body.setClaimedBed(null);
                    if (body.isSleeping()) {
                        body.stopSleeping();
                    }
                }
            }
        }
        region.removeInhabitant(civilianId);
    }

    /**
     * Detaches all inhabitants from {@code region} and releases any claimed beds inside it on both master records and
     * loaded civilian entities (D7).
     */
    public static void detachAllInhabitants(ServerLevel overworld, EmpireSavedData data, RegionRecord region) {
        if (region.inhabitants().isEmpty()) {
            return;
        }
        for (UUID civilianId : List.copyOf(region.inhabitants())) {
            CivilianRecord record = data.civilian(civilianId);
            if (record != null) {
                if (record.bed() != null && region.containsBlock(record.bed())) {
                    CivilianBeds.release(overworld, record.bed());
                    record.setBed(null);
                }
                if (overworld.getEntity(record.body()) instanceof CivilianEntity body) {
                    if (body.claimedBed() != null && region.containsBlock(body.claimedBed())) {
                        CivilianBeds.release(overworld, body.claimedBed());
                        body.setClaimedBed(null);
                        if (body.isSleeping()) {
                            body.stopSleeping();
                        }
                    }
                }
            }
        }
        region.clearInhabitants();
        data.setDirty();
    }

    /** True if {@code regionId} currently has a pending scheduled update (for testing/diagnostics). */
    public static boolean hasScheduledUpdate(UUID regionId) {
        return SCHEDULED_UPDATES.containsKey(regionId);
    }

    /** True if {@code regionId} currently has a pending 3-day expiry trigger (for testing/diagnostics). */
    public static boolean hasScheduledExpiry(UUID regionId) {
        return SCHEDULED_EXPIRIES.containsKey(regionId);
    }

    private RegionManager() {
    }
}
