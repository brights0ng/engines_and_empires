package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;

/** Bees stay in the hive while it rains on the hive (phase 6a). */
@Mixin(BeehiveBlockEntity.class)
public abstract class BeehiveWeatherMixin {

    @WrapOperation(method = "releaseOccupant", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
    private static boolean engines_and_empires$rainOnHive(Level level, Operation<Boolean> original,
                                                        @Local(argsOnly = true, ordinal = 0) BlockPos pos) {
        if (WeatherOwnership.owns(level)) {
            return WeatherQueries.precipitationOver(level, pos);
        }
        return original.call(level);
    }
}
