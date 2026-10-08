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
import net.minecraft.world.level.block.LightningRodBlock;

/** A lightning rod's sparks (client particles) under a thunderstorm over the rod, not the camera's (phase 6a). */
@Mixin(LightningRodBlock.class)
public abstract class LightningRodWeatherMixin {

    @WrapOperation(method = "animateTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isThundering()Z"))
    private boolean engines_and_empires$thunderOverRod(Level level, Operation<Boolean> original,
                                                     @Local(argsOnly = true) BlockPos pos) {
        if (WeatherOwnership.owns(level)) {
            return WeatherQueries.thunderOver(level, pos);
        }
        return original.call(level);
    }
}
