package dev.brights0ng.enginesandempires.weather.sim.world;

import java.util.List;

import dev.brights0ng.enginesandempires.weather.sim.PressureField;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;
import dev.brights0ng.enginesandempires.weather.wind.WindColumn;
import net.minecraft.server.level.ServerLevel;

/**
 * The atmosphere's diagnostics in the world (phase 2 of {@code claude/weather-backbone-plan.md}): pressure and wind
 * from the weather systems and the jet ({@link PressureField}). Phase 4 adds lift, instability and the rest. Overworld
 * only; elsewhere there is no wind and normal pressure.
 *
 * <p>Reads use the systems' snapshot and season from the last systems step (perf report 2026-10-05): this is called
 * for every ship every physics tick.
 */
public final class Atmosphere {

    /** The wind over (x, z), or null outside the Overworld. */
    public static WindColumn wind(ServerLevel level, double x, double z) {
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            return null;
        }
        double[] w = PressureField.wind(sim.snapshot(), sim.jet(), x, z, sim.seconds(), sim.cachedSeason(), sim.seed());
        // Storm outflow blows along the ground (phase 4b).
        double[] g = CloudWorld.gust(level, x, z);
        return new WindColumn(cap(w[0] + g[0]), cap(w[1] + g[1]), w[2], w[3]);
    }

    private static double cap(double v) {
        return Math.max(-40, Math.min(40, v));
    }

    /** The pressure at (x, z), hPa (normal outside the Overworld). */
    public static double pressure(ServerLevel level, double x, double z) {
        WeatherSim sim = WeatherSim.of(level);
        return sim == null ? PressureField.NORMAL : sim.snapshot().pressure(x, z);
    }

    /** The systems in the Overworld (empty elsewhere). */
    public static List<WeatherSystem> systems(ServerLevel level) {
        WeatherSim sim = WeatherSim.of(level);
        return sim == null ? List.of() : sim.systems();
    }

    private Atmosphere() {
    }
}
