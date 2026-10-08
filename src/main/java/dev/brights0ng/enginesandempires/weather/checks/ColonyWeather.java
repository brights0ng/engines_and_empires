package dev.brights0ng.enginesandempires.weather.checks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The shared answer for MineColonies' {@code level.isRaining()} calls (weather phase 6a): anything falling (rain, snow,
 * sleet, hail) over {@code pos} where the pack owns the weather, MineColonies' own answer elsewhere. Colonists going
 * home in rain therefore go home in hail too (the phase 5c item).
 */
public final class ColonyWeather {

    public static boolean rainingAt(Level level, BlockPos pos, Operation<Boolean> original) {
        if (pos != null && WeatherOwnership.owns(level)) {
            return WeatherQueries.precipitationOver(level, pos);
        }
        return original.call(level);
    }

    private ColonyWeather() {
    }
}
