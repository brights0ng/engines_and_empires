package dev.brights0ng.enginesandempires.weather.cloud;

import java.util.List;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;
import net.minecraft.server.level.ServerLevel;

/**
 * Where the clouds come from: on the server the simulation's own, on the client the ones the server has sent.
 *
 * <p>Phase 0 of the weather backbone (2026-10-05, {@code claude/weather-backbone-plan.md}): Project Atmosphere is gone
 * and the simulation doesn't exist yet, so there are no clouds and the skies stay clear. Phase 4 (the cloud spawner and
 * its sync) fills both in. Everything downstream (the renderer, the shared rain query, fog) already reads from here.
 */
public final class CloudSources {

    /** Every active cloud in {@code level}. */
    public static List<CloudShape> server(ServerLevel level) {
        return List.of();
    }

    /** Every cloud the server has sent this client, in any dimension. */
    public static List<CloudShape> client() {
        return List.of();
    }

    /** Whether anything produces clouds yet, for debug output. */
    public static String describe() {
        return "none yet (the weather simulation arrives in a later phase)";
    }

    private CloudSources() {
    }
}
