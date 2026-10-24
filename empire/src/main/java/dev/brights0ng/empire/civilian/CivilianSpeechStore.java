// Written by Claude.
package dev.brights0ng.empire.civilian;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * The line each civilian is saying, by entity id, as the client last heard it ({@link CivilianNetwork}). Common-safe (no
 * client imports) so the payload handler can live with the payload; only the client fills and reads it, on the main
 * thread. Cleared on logout.
 */
public final class CivilianSpeechStore {

    /** Lines older than this are pruned when the store grows; longer than any bubble lasts. */
    private static final long PRUNE_AGE = 400;
    private static final int PRUNE_SIZE = 256;

    public record Bubble(String key, long startGameTime) {
    }

    private static final Int2ObjectMap<Bubble> BUBBLES = new Int2ObjectOpenHashMap<>();

    public static void put(int entityId, String key, long gameTime) {
        if (BUBBLES.size() >= PRUNE_SIZE) {
            BUBBLES.values().removeIf(b -> gameTime - b.startGameTime() > PRUNE_AGE);
        }
        BUBBLES.put(entityId, new Bubble(key, gameTime));
    }

    @Nullable
    public static Bubble get(int entityId) {
        return BUBBLES.get(entityId);
    }

    public static void remove(int entityId) {
        BUBBLES.remove(entityId);
    }

    public static void clear() {
        BUBBLES.clear();
    }

    private CivilianSpeechStore() {
    }
}
