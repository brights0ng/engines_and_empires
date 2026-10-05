package dev.brights0ng.enginesandempires.weather.wind;

import net.minecraft.server.level.ServerLevel;

/**
 * Where the wind comes from: a debug/test override if one is set ({@code /eae wind set}, game tests), otherwise the
 * weather simulation, otherwise none (then nothing is pushed).
 *
 * <p>Phase 0 of the weather backbone (2026-10-05): Project Atmosphere is gone and the simulation's wind arrives in
 * phase 4, so only the override blows for now.
 */
public final class WindSources {

    private static volatile WindColumn override;

    /** The wind over (x, z), or null when there is no wind source. */
    public static WindColumn column(ServerLevel level, double x, double z) {
        WindColumn forced = override;
        if (forced != null) {
            return forced;
        }
        return null;
    }

    /** What the wind comes from now, for the debug command. */
    public static String describe() {
        if (override != null) {
            return "override";
        }
        return "none (the weather simulation's wind arrives in a later phase)";
    }

    /** Forces the same wind everywhere, at every height, or clears it with null. */
    public static void setOverride(WindColumn wind) {
        override = wind;
    }

    public static WindColumn override() {
        return override;
    }

    private WindSources() {
    }
}
