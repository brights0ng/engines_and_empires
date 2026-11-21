// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.List;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Tracks block edits near regions, periodically validates loaded regions, detaches inhabitants on invalidation,
 * recovers fixed regions, and expires invalid {@code AUTO} regions after 3 MC days (D7).
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class RegionManager {

    /** Ticks between periodic region validation sweeps (provisional default). */
    public static final int VALIDATE_INTERVAL = 100;

    @SubscribeEvent
    static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            markDirtyNear(level, event.getPos());
        }
    }

    @SubscribeEvent
    static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            markDirtyNear(level, event.getPos());
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % VALIDATE_INTERVAL == 0) {
            tick(event.getServer().overworld());
        }
    }

    /** Marks any region whose bounding box inflated by 1 block contains {@code pos} dirty for validation. */
    public static void markDirtyNear(ServerLevel overworld, BlockPos pos) {
        EmpireSavedData data = EmpireSavedData.of(overworld);
        for (RegionRecord region : data.allRegions()) {
            if (region.isWithinInflatedBounds(pos, 1)) {
                region.setDirtyFlag(true);
            }
        }
    }

    /** Runs one validation and expiry sweep over all regions in the Overworld. */
    public static void tick(ServerLevel overworld) {
        EmpireSavedData data = EmpireSavedData.of(overworld);
        long gameTime = overworld.getGameTime();
        for (RegionRecord region : List.copyOf(data.allRegions())) {
            validateRegion(overworld, data, region, gameTime);
        }
    }

    /**
     * Validates a single region if its chunks are loaded, applying {@code VALID <-> INVALID} transitions, immediate
     * inhabitant/bed detachment on invalidation, and 3-day auto-deletion for expired {@code AUTO} regions.
     */
    public static void validateRegion(ServerLevel overworld, EmpireSavedData data, RegionRecord region, long gameTime) {
        EmpireRecord empire = data.getEmpire(region.empireId());
        if (empire == null) {
            detachAllInhabitants(overworld, data, region);
            data.removeRegion(region.id());
            return;
        }

        if (HomeRegionScanner.areChunksLoaded(overworld, region)) {
            RegionInvalidReason reason = validateByType(overworld, data, empire, region);
            region.setDirtyFlag(false);
            if (reason == RegionInvalidReason.NONE) {
                if (!region.valid()) {
                    region.markValid();
                    data.setDirty();
                }
            } else {
                boolean wasValid = region.valid();
                RegionInvalidReason prevReason = region.invalidReason();
                region.markInvalid(reason, gameTime);
                if (wasValid || prevReason != reason) {
                    data.setDirty();
                }
                if (wasValid || !region.inhabitants().isEmpty()) {
                    detachAllInhabitants(overworld, data, region);
                }
            }
        }

        if (RegionRules.shouldAutoDelete(region.origin(), region.valid(), region.invalidSinceGameTime(), gameTime)) {
            detachAllInhabitants(overworld, data, region);
            data.removeRegion(region.id());
        }
    }

    private static RegionInvalidReason validateByType(ServerLevel overworld, EmpireSavedData data,
                                                      EmpireRecord empire, RegionRecord region) {
        return switch (region.type()) {
            case HOME -> HomeRegionScanner.validate(overworld, data, empire, region);
        };
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

    private RegionManager() {
    }
}
