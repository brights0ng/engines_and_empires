package dev.brights0ng.enginesandempires.weather.cloud.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;

/**
 * The clouds the server has sent this client ({@code CloudSyncPayload}), kept as {@link SimCloud}s and turned into
 * shapes at the client's game time. Plain static state with no Minecraft classes, so tests can drive it; the client
 * thread calls everything here (payloads are handled on the main thread).
 */
public final class CloudSyncClient {

    /** The pack's weather lives in the Overworld only. */
    public static final String DIMENSION = "minecraft:overworld";

    private static final Map<UUID, SimCloud> CLOUDS = new HashMap<>();
    private static List<CloudShape> shapes = List.of();
    private static double shapesTime = Double.NaN;

    /** Applies one sync. */
    public static void accept(boolean reset, List<SimCloud> upserts, List<UUID> removed) {
        if (reset) {
            CLOUDS.clear();
        }
        for (UUID id : removed) {
            CLOUDS.remove(id);
        }
        for (SimCloud c : upserts) {
            CLOUDS.put(c.id, c);
        }
        shapesTime = Double.NaN;
    }

    public static void clear() {
        CLOUDS.clear();
        shapes = List.of();
        shapesTime = Double.NaN;
    }

    /** Every cloud's domes at game time {@code now} (worked out once per tick). */
    public static List<CloudShape> shapes(double now) {
        if (now != shapesTime) {
            List<CloudShape> out = new ArrayList<>();
            for (SimCloud c : CLOUDS.values()) {
                if (!c.over((long) Math.floor(now))) {
                    out.addAll(c.shapes(DIMENSION, now));
                }
            }
            shapes = out;
            shapesTime = now;
        }
        return shapes;
    }

    /** How many clouds this client knows of. */
    public static int count() {
        return CLOUDS.size();
    }

    private CloudSyncClient() {
    }
}
