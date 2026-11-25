// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * Client-side cache of the player's empire's nearby regions and active Administrative Ledger selection, fed by
 * {@link RegionNetwork} and read by {@link dev.brights0ng.empire.client.RegionRenderer} and client-side click
 * prediction in {@link dev.brights0ng.empire.ledger.LedgerInteractions}.
 */
public final class RegionClientCache {

    /** Minimum time between periodic refresh requests when the player has not changed chunk. */
    private static final long REFRESH_MILLIS = 2000L;

    private static List<RegionPayloads.RegionSnapshot> regions = List.of();
    private static final Long2ObjectOpenHashMap<RegionPayloads.RegionSnapshot> BLOCK_INDEX = new Long2ObjectOpenHashMap<>();
    @Nullable
    private static UUID selectedRegionId;
    private static int revision;
    private static boolean hasAsked;
    private static int lastChunkX;
    private static int lastChunkZ;
    private static int lastRadius;
    private static long lastAskedMillis;

    public static synchronized void accept(RegionPayloads.Sync sync) {
        regions = List.copyOf(sync.regions());
        selectedRegionId = sync.selectedRegionId().orElse(null);
        BLOCK_INDEX.clear();
        for (RegionPayloads.RegionSnapshot snap : regions) {
            for (long key : snap.blocks()) {
                BLOCK_INDEX.put(key, snap);
            }
            for (long key : snap.secondaryBlocks()) {
                BLOCK_INDEX.put(key, snap);
            }
            for (long key : snap.separators()) {
                BLOCK_INDEX.put(key, snap);
            }
        }
        revision++;
    }

    public static synchronized List<RegionPayloads.RegionSnapshot> regions() {
        return regions;
    }

    @Nullable
    public static synchronized UUID selectedRegionId() {
        return selectedRegionId;
    }

    /** Returns the cached snapshot matching the currently selected region id, if nearby/cached. */
    @Nullable
    public static synchronized RegionPayloads.RegionSnapshot selectedRegion() {
        if (selectedRegionId == null) {
            return null;
        }
        for (RegionPayloads.RegionSnapshot snap : regions) {
            if (selectedRegionId.equals(snap.id())) {
                return snap;
            }
        }
        return null;
    }

    /** Returns the cached region snapshot containing {@code pos}, or {@code null}. */
    @Nullable
    public static synchronized RegionPayloads.RegionSnapshot regionAt(BlockPos pos) {
        return BLOCK_INDEX.get(pos.asLong());
    }

    /** Clears the local selected region immediately (used when left-clicking while holding the ledger). */
    public static synchronized void clearSelectionLocally() {
        if (selectedRegionId != null) {
            selectedRegionId = null;
            revision++;
        }
    }

    public static synchronized int revision() {
        return revision;
    }

    /** Returns true if the client should send a new {@link RegionPayloads.Request} for {@code (cx, cz, radius)}. */
    public static synchronized boolean shouldAsk(int cx, int cz, int radius, long nowMillis) {
        if (!hasAsked || cx != lastChunkX || cz != lastChunkZ || radius != lastRadius
                || nowMillis - lastAskedMillis >= REFRESH_MILLIS || nowMillis < lastAskedMillis) {
            hasAsked = true;
            lastChunkX = cx;
            lastChunkZ = cz;
            lastRadius = radius;
            lastAskedMillis = nowMillis;
            return true;
        }
        return false;
    }

    /** Drops cached regions, selection, and request throttling state (called on logout or when leaving the Overworld). */
    public static synchronized void clear() {
        regions = List.of();
        BLOCK_INDEX.clear();
        selectedRegionId = null;
        revision++;
        hasAsked = false;
    }

    private RegionClientCache() {
    }
}
