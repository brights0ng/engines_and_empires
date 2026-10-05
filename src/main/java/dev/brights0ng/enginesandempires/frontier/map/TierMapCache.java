package dev.brights0ng.enginesandempires.frontier.map;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * What the client knows of the tiers, for its maps: every column it has been told about (so panning back is instant), and
 * the incursions under way. Filled by {@link TierMapPayloads.Answer}s. Touches no client-only classes, so it is safe to
 * name from common code; only the client ever fills it.
 */
public final class TierMapCache {

    /** Beyond this many columns the cache starts over rather than grow without end. */
    private static final int MAX_COLUMNS = 400_000;

    private static final Long2ByteOpenHashMap TIERS = new Long2ByteOpenHashMap();
    private static volatile List<TierMapPayloads.Marker> markers = List.of();
    private static final Map<Object, TierRequests.Asked> ASKED = new HashMap<>();

    /** A column's tier ordinal, 0 (Frontier) if unknown. */
    public static int tier(int cx, int cz) {
        synchronized (TIERS) {
            return TIERS.get(ChunkPos.asLong(cx, cz));
        }
    }

    public static List<TierMapPayloads.Marker> markers() {
        return markers;
    }

    /** Takes in an answer from the server. */
    public static void accept(TierMapPayloads.Answer answer) {
        byte[] tiers = TierGrid.unpack(answer.packed(), answer.size() * answer.size());
        synchronized (TIERS) {
            if (TIERS.size() + tiers.length > MAX_COLUMNS) {
                TIERS.clear();
            }
            for (int dz = 0; dz < answer.size(); dz++) {
                for (int dx = 0; dx < answer.size(); dx++) {
                    byte tier = tiers[dz * answer.size() + dx];
                    long key = ChunkPos.asLong(answer.x() + dx, answer.z() + dz);
                    if (tier == 0) {
                        TIERS.remove(key);
                    } else {
                        TIERS.put(key, tier);
                    }
                }
            }
        }
        markers = List.copyOf(answer.markers());
    }

    /**
     * Whether the map known by {@code key} (a logger's position, or the display) should ask for this area now (see
     * {@link TierRequests}); if so, it is noted as asked.
     */
    public static boolean shouldAsk(Object key, int centreX, int centreZ, int radius, long now) {
        synchronized (ASKED) {
            if (ASKED.size() > 64) {
                ASKED.values().removeIf(asked -> now - asked.at() > 60_000);
            }
            if (!TierRequests.shouldAsk(ASKED.get(key), centreX, centreZ, radius, now)) {
                return false;
            }
            ASKED.put(key, new TierRequests.Asked(centreX, centreZ, radius, now));
            return true;
        }
    }

    /** Forgets everything (leaving a world). */
    public static void clear() {
        synchronized (TIERS) {
            TIERS.clear();
        }
        synchronized (ASKED) {
            ASKED.clear();
        }
        markers = List.of();
    }

    private TierMapCache() {
    }
}
