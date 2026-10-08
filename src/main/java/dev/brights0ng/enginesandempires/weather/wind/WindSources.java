package dev.brights0ng.enginesandempires.weather.wind;

import dev.brights0ng.enginesandempires.weather.sim.world.Atmosphere;
import net.minecraft.server.level.ServerLevel;

/**
 * Where the wind comes from: a debug/test override if one is set ({@code /eae wind set}, game tests), otherwise the
 * weather simulation's atmosphere ({@link Atmosphere}: the jet and the pressure systems, Overworld only), otherwise
 * none (then nothing is pushed).
 */
public final class WindSources {

    private static volatile WindColumn override;

    /** The wind over (x, z), or null when there is no wind source. */
    public static WindColumn column(ServerLevel level, double x, double z) {
        WindColumn forced = override;
        if (forced != null) {
            return forced;
        }
        return Atmosphere.wind(level, x, z);
    }

    /** What the wind comes from now, for the debug command. */
    public static String describe() {
        if (override != null) {
            return "override";
        }
        return "the weather simulation (jet and pressure systems; Overworld only)";
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
