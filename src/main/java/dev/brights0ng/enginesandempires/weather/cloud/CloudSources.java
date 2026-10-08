package dev.brights0ng.enginesandempires.weather.cloud;

import java.util.List;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSyncClient;
import dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld;
import net.minecraft.server.level.ServerLevel;

/**
 * Where the clouds come from: on the server the simulation's own ({@link CloudWorld}), on the client the ones the
 * server has sent ({@link CloudSyncClient}). Everything downstream (the renderer, the shared rain query, fog) reads
 * from here.
 */
public final class CloudSources {

    /** Every active cloud in {@code level}, as of its current tick. */
    public static List<CloudShape> server(ServerLevel level) {
        return CloudWorld.shapes(level);
    }

    /** Every cloud the server has sent this client, at client game time {@code time}. */
    public static List<CloudShape> client(double time) {
        return CloudSyncClient.shapes(time);
    }

    /** Where clouds come from, for debug output. */
    public static String describe() {
        return "the weather simulation (" + CloudSyncClient.count() + " clouds synced)";
    }

    private CloudSources() {
    }
}
