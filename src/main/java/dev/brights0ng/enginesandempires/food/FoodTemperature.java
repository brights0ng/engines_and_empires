package dev.brights0ng.enginesandempires.food;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.climate.Baseline;
import dev.brights0ng.enginesandempires.weather.climate.Climate;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.climate.UndergroundProbe;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatWorld;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.ships.ShipCover;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The temperature food sees (food by temperature, 2026-10-15; {@code claude/food-temperature.md}): the air where it is
 * (underground included) plus the heat simulation's room there; on a ship, the air where the ship is and the ship's
 * room. Not wind chill, sun or radiant warmth. NaN where the pack doesn't own the weather (other dimensions): food
 * there ages by the clock alone.
 */
public final class FoodTemperature {

    /** Game tests: temperatures used everywhere instead of the world's (NaN: none). */
    private static volatile double overrideNow = Double.NaN;
    private static volatile double overrideSeasonal = Double.NaN;

    /** Game tests: every place reads {@code now} C, and {@code seasonal} C for long gaps, until cleared. */
    public static void setOverride(double now, double seasonal) {
        overrideNow = now;
        overrideSeasonal = seasonal;
    }

    public static void clearOverride() {
        setOverride(Double.NaN, Double.NaN);
    }

    /** The temperature at block {@code pos} (a container, a belt), C, or NaN. */
    public static double at(ServerLevel level, BlockPos pos) {
        if (!Double.isNaN(overrideNow)) {
            return overrideNow;
        }
        if (!WeatherOwnership.owns(level)) {
            return Double.NaN;
        }
        double air;
        if (ShipCover.inPlot(level, pos)) {
            Vec3 w = ShipCover.worldCentre(level, pos);
            if (w == null) {
                return Double.NaN;
            }
            air = Climate.sample(level, w.x, w.y, w.z).temperature();
        } else {
            air = Temperature.at(level, pos);
        }
        return air + room(level, Vec3.atCenterOf(pos));
    }

    /** The temperature at an entity (a player, an item on the ground), C, or NaN. */
    public static double at(ServerLevel level, Entity entity) {
        if (!Double.isNaN(overrideNow)) {
            return overrideNow;
        }
        if (!WeatherOwnership.owns(level)) {
            return Double.NaN;
        }
        BlockPos feet = entity.blockPosition();
        double air;
        if (ShipCover.inPlot(level, feet)) {
            Vec3 w = ShipCover.worldCentre(level, feet);
            if (w == null) {
                return Double.NaN;
            }
            air = Climate.sample(level, w.x, w.y, w.z).temperature();
        } else {
            air = Temperature.at(level, feet);
        }
        return air + room(level, entity.position().add(0, Math.min(0.9, entity.getBbHeight() / 2), 0));
    }

    /**
     * The temperature food at {@code pos} sat at while nobody was there to see (an unloaded chunk, a long gap): the day's
     * mean for this time of year, without air masses (decision 8), underground included, plus the room as it is now.
     */
    public static double seasonal(ServerLevel level, BlockPos pos) {
        if (!Double.isNaN(overrideSeasonal)) {
            return overrideSeasonal;
        }
        if (!WeatherOwnership.owns(level)) {
            return Double.NaN;
        }
        double t;
        if (ShipCover.inPlot(level, pos)) {
            Vec3 w = ShipCover.worldCentre(level, pos);
            if (w == null) {
                return Double.NaN;
            }
            t = mean(Climate.sample(level, w.x, w.y, w.z));
        } else {
            double outside = mean(Climate.sample(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            t = UndergroundProbe.read(level, pos, outside).temperature();
        }
        return t + room(level, Vec3.atCenterOf(pos));
    }

    private static double mean(Baseline.Sample s) {
        return s.dailyMean() - s.anomaly();
    }

    private static double room(ServerLevel level, Vec3 at) {
        HeatWorld heat = HeatWorld.of(level);
        return heat != null && WeatherConfig.heat().enabled() ? heat.roomAt(at) : 0;
    }

    private FoodTemperature() {
    }
}
