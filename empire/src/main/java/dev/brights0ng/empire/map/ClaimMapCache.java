// Written by Gemini.
package dev.brights0ng.empire.map;

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
        bonfires = List.copyOf(answer.bonfires());
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
