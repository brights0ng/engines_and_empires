// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.List;

/**
 * Client-side cache of the player's empire's nearby regions, fed by {@link RegionNetwork} and read by
 * {@link dev.brights0ng.empire.client.RegionRenderer}.
 */
public final class RegionClientCache {

    /** Minimum time between periodic refresh requests when the player has not changed chunk. */
    private static final long REFRESH_MILLIS = 2000L;

    private static List<RegionPayloads.RegionSnapshot> regions = List.of();
    private static int revision;
    private static boolean hasAsked;
    private static int lastChunkX;
    private static int lastChunkZ;
    private static int lastRadius;
    private static long lastAskedMillis;

    public static synchronized void accept(RegionPayloads.Sync sync) {
        regions = List.copyOf(sync.regions());
        revision++;
    }

    public static synchronized List<RegionPayloads.RegionSnapshot> regions() {
        return regions;
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

    /** Drops cached regions and request throttling state (called on logout or when leaving the Overworld). */
    public static synchronized void clear() {
        regions = List.of();
        revision++;
        hasAsked = false;
    }

    private RegionClientCache() {
    }
}
