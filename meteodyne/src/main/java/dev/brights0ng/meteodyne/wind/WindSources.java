package dev.brights0ng.meteodyne.wind;

import dev.brights0ng.meteodyne.sim.world.Atmosphere;
import dev.brights0ng.paradynelib.services.WindProvider;
import dev.brights0ng.paradynelib.wind.WindColumn;
import net.minecraft.server.level.ServerLevel;

/**
 * Where the wind comes from: a debug/test override if one is set ({@code /meteodyne wind set}, game tests), otherwise the
 * weather simulation's atmosphere ({@link Atmosphere}: the jet and the pressure systems, Overworld only), otherwise
 * none (then nothing is pushed). The library's wind asks it through {@link #PROVIDER} (split seam S2).
 */
public final class WindSources {

    /** Meteodyne's answer to the library's {@link WindProvider}: the wind as this class has it. */
    public static final WindProvider PROVIDER = new WindProvider() {
        @Override
        public WindColumn column(ServerLevel level, double x, double z) {
            return WindSources.column(level, x, z);
        }

        @Override
        public String describe() {
            return WindSources.describe();
        }

        @Override
        public void setOverride(WindColumn wind) {
            WindSources.setOverride(wind);
        }
    };

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
