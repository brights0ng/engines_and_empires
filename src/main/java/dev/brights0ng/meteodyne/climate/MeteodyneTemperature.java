package dev.brights0ng.meteodyne.climate;

import dev.brights0ng.meteodyne.WeatherOwnership;
import dev.brights0ng.meteodyne.heat.world.HeatWorld;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import dev.brights0ng.paradynelib.ships.ShipCover;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Meteodyne's answer to the library's {@link TemperatureProvider} (split seam S1; food by temperature, 2026-10-15,
 * {@code claude/food-temperature.md}): the air where the pack owns the weather (underground included; on a ship, the air
 * where the ship is), the heat simulation's room, and the seasonal mean for long gaps. NaN where the pack doesn't own
 * the weather (other dimensions).
 */
public final class MeteodyneTemperature implements TemperatureProvider {

    public static final MeteodyneTemperature INSTANCE = new MeteodyneTemperature();

    @Override
    public double air(ServerLevel level, BlockPos pos) {
        if (!WeatherOwnership.owns(level)) {
            return Double.NaN;
        }
        if (ShipCover.inPlot(level, pos)) {
            Vec3 w = ShipCover.worldCentre(level, pos);
            if (w == null) {
                return Double.NaN;
            }
            return Climate.sample(level, w.x, w.y, w.z).temperature();
        }
        return Temperature.at(level, pos);
    }

    @Override
    public double room(ServerLevel level, Vec3 at) {
        if (!WeatherOwnership.owns(level)) {
            return 0;
        }
        HeatWorld heat = HeatWorld.of(level);
        return heat != null && WeatherConfig.heat().enabled() ? heat.roomAt(at) : 0;
    }

    /** The day's mean for this time of year, without air masses (decision 8), underground included. */
    @Override
    public double seasonal(ServerLevel level, BlockPos pos) {
        if (!WeatherOwnership.owns(level)) {
            return Double.NaN;
        }
        if (ShipCover.inPlot(level, pos)) {
            Vec3 w = ShipCover.worldCentre(level, pos);
            if (w == null) {
                return Double.NaN;
            }
            return mean(Climate.sample(level, w.x, w.y, w.z));
        }
        double outside = mean(Climate.sample(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        return UndergroundProbe.read(level, pos, outside).temperature();
    }

    private static double mean(Baseline.Sample s) {
        return s.dailyMean() - s.anomaly();
    }

    private MeteodyneTemperature() {
    }
}
