// Written by Gemini; bonfire markers kept across answers by Claude.
package dev.brights0ng.empire.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.empire.rules.ClaimGrid;
import dev.brights0ng.empire.rules.ClaimRequests;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * What the client knows of empire claims, for its maps: every column it has been told about, and
 * the active main bonfires. Filled by {@link ClaimMapPayloads.Answer}s. Touches no client-only classes, so it is safe to
 * name from common code; only the client ever fills it.
 */
public final class ClaimMapCache {

    /** Beyond this many columns the cache starts over rather than grow without end. */
    private static final int MAX_COLUMNS = 400_000;
    /** Beyond this many main bonfire markers the oldest are dropped. */
    private static final int MAX_BONFIRES = 4_096;

    private static final Long2ByteOpenHashMap CLAIMS = new Long2ByteOpenHashMap();
    private static volatile List<ClaimMapPayloads.BonfireMarker> bonfires = List.of();
    private static final Map<Object, ClaimRequests.Asked> ASKED = new HashMap<>();

    /** A column's claim state (0 Unclaimed, 1 Our Empire, 2 Other Empire). */
    public static int claim(int cx, int cz) {
        synchronized (CLAIMS) {
            return CLAIMS.get(ChunkPos.asLong(cx, cz));
        }
    }

    public static List<ClaimMapPayloads.BonfireMarker> bonfires() {
        return bonfires;
    }

    /**
     * The protected (divine) land a chunk is in, as the client knows it: {@link ClaimGrid#OUR_EMPIRE} or
     * {@link ClaimGrid#OTHER_EMPIRE} for a chunk in the 3x3 around a known main bonfire of that side (and claimed by
     * it), else {@link ClaimGrid#UNCLAIMED}. Only an empire with a main bonfire has protected land.
     */
    public static int protection(int cx, int cz) {
        for (ClaimMapPayloads.BonfireMarker marker : bonfires) {
            if (Math.abs((marker.x() >> 4) - cx) <= 1 && Math.abs((marker.z() >> 4) - cz) <= 1) {
                int side = marker.ourEmpire() ? ClaimGrid.OUR_EMPIRE : ClaimGrid.OTHER_EMPIRE;
                if (claim(cx, cz) == side) {
                    return side;
                }
            }
        }
        return ClaimGrid.UNCLAIMED;
    }

    /** Takes in an answer from the server. */
    public static void accept(ClaimMapPayloads.Answer answer) {
        byte[] states = ClaimGrid.unpack(answer.packed(), answer.size() * answer.size());
        synchronized (CLAIMS) {
            if (CLAIMS.size() + states.length > MAX_COLUMNS) {
                CLAIMS.clear();
            }
            for (int dz = 0; dz < answer.size(); dz++) {
                for (int dx = 0; dx < answer.size(); dx++) {
                    byte state = states[dz * answer.size() + dx];
                    long key = ChunkPos.asLong(answer.x() + dx, answer.z() + dz);
                    if (state == ClaimGrid.UNCLAIMED) {
                        CLAIMS.remove(key);
                    } else {
                        CLAIMS.put(key, state);
                    }
                }
            }
        }
        // The answer lists the main bonfires inside its square: replace the ones known there, keep the rest (another
        // map, or the talc borders, may have asked about somewhere else).
        int minX = answer.x() << 4;
        int minZ = answer.z() << 4;
        int maxX = (answer.x() + answer.size()) << 4;
        int maxZ = (answer.z() + answer.size()) << 4;
        List<ClaimMapPayloads.BonfireMarker> merged = new ArrayList<>();
        for (ClaimMapPayloads.BonfireMarker marker : bonfires) {
            if (marker.x() < minX || marker.x() > maxX || marker.z() < minZ || marker.z() > maxZ) {
                merged.add(marker);
            }
        }
        merged.addAll(answer.bonfires());
        if (merged.size() > MAX_BONFIRES) {
            merged = merged.subList(merged.size() - MAX_BONFIRES, merged.size());
        }
        bonfires = List.copyOf(merged);
    }

    /**
     * Whether the map known by {@code key} (a logger's position, or the display) should ask for this area now (see
     * {@link ClaimRequests}); if so, it is noted as asked.
     */
    public static boolean shouldAsk(Object key, int centreX, int centreZ, int radius, long now) {
        synchronized (ASKED) {
            if (ASKED.size() > 64) {
                ASKED.values().removeIf(asked -> now - asked.at() > 60_000);
            }
            if (!ClaimRequests.shouldAsk(ASKED.get(key), centreX, centreZ, radius, now)) {
                return false;
            }
            ASKED.put(key, new ClaimRequests.Asked(centreX, centreZ, radius, now));
            return true;
        }
    }

    /** Forgets everything (leaving a world). */
    public static void clear() {
        synchronized (CLAIMS) {
            CLAIMS.clear();
        }
        synchronized (ASKED) {
            ASKED.clear();
        }
        bonfires = List.of();
    }

    private ClaimMapCache() {
    }
}
